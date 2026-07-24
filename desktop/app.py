from __future__ import annotations

import concurrent.futures
import http.client
import json
import mimetypes
import os
import queue
import shutil
import socket
import sys
import threading
import time
import tkinter as tk
import urllib.parse
import uuid
import webbrowser
from dataclasses import dataclass, asdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from tkinter import filedialog, messagebox, ttk
from typing import Any, Dict, Iterable, List, Optional, Set, Tuple

try:
    import qrcode
    from PIL import Image, ImageTk
except Exception:
    qrcode = None
    Image = None
    ImageTk = None

APP_NAME = "SendViaLocalNet"
DISPLAY_NAME = "نقل محلي Pro"
PORT = 5051
BUFFER_SIZE = 1024 * 256
CLIENT_TIMEOUT = 35
QUEUE_TTL = 24 * 60 * 60


def resource_path(relative: str) -> Path:
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    return base / relative


def app_data_dir() -> Path:
    root = Path(os.getenv("APPDATA") or (Path.home() / ".config"))
    path = root / APP_NAME
    path.mkdir(parents=True, exist_ok=True)
    return path


def downloads_dir() -> Path:
    path = Path.home() / "Downloads" / APP_NAME
    path.mkdir(parents=True, exist_ok=True)
    return path


def safe_filename(name: str) -> str:
    value = (name or f"file_{int(time.time())}.bin").strip()
    for char in '\\/:*?"<>|':
        value = value.replace(char, "_")
    return value[:220] or f"file_{int(time.time())}.bin"


def unique_path(directory: Path, name: str) -> Path:
    target = directory / safe_filename(name)
    if not target.exists():
        return target
    stem, suffix = target.stem, target.suffix
    index = 2
    while target.exists():
        target = directory / f"{stem} ({index}){suffix}"
        index += 1
    return target


def format_size(size: int) -> str:
    value = float(size)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if value < 1024 or unit == "TB":
            return f"{value:.1f} {unit}" if unit != "B" else f"{int(value)} B"
        value /= 1024
    return f"{size} B"


def local_ip() -> str:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        ip = sock.getsockname()[0]
        if ip and not ip.startswith("127."):
            return ip
    except OSError:
        pass
    finally:
        sock.close()
    try:
        return socket.gethostbyname(socket.gethostname())
    except OSError:
        return "127.0.0.1"


@dataclass
class DirectDevice:
    name: str
    ip: str
    selected: bool = True


class PersistentStore:
    def __init__(self) -> None:
        self.base = app_data_dir()
        self.config_path = self.base / "config.json"
        self.queue_path = self.base / "queue.json"
        self.queue_files = self.base / "queue_files"
        self.queue_files.mkdir(exist_ok=True)
        self.lock = threading.RLock()
        self.config: Dict[str, Any] = self._read_json(self.config_path, {})
        self.transfers: Dict[str, Dict[str, Any]] = self._read_json(self.queue_path, {})

    @staticmethod
    def _read_json(path: Path, fallback: Any) -> Any:
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            return fallback

    @staticmethod
    def _write_json(path: Path, value: Any) -> None:
        temp = path.with_suffix(path.suffix + ".tmp")
        temp.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
        temp.replace(path)

    def get_device_name(self) -> str:
        return str(self.config.get("device_name") or socket.gethostname() or "كمبيوتر Windows")

    def set_device_name(self, name: str) -> None:
        with self.lock:
            self.config["device_name"] = name.strip() or "كمبيوتر Windows"
            self._write_json(self.config_path, self.config)

    def get_devices(self) -> List[DirectDevice]:
        result: List[DirectDevice] = []
        for item in self.config.get("devices", []):
            try:
                result.append(DirectDevice(str(item["name"]), str(item["ip"]), bool(item.get("selected", True))))
            except Exception:
                continue
        return result

    def save_devices(self, devices: Iterable[DirectDevice]) -> None:
        with self.lock:
            self.config["devices"] = [asdict(item) for item in devices]
            self._write_json(self.config_path, self.config)

    def queue_file(self, source: Path, recipients: List[str], sender_name: str, original_name: Optional[str] = None) -> str:
        transfer_id = uuid.uuid4().hex
        target = self.queue_files / f"{transfer_id}.bin"
        shutil.copyfile(str(source), str(target))
        with self.lock:
            self.transfers[transfer_id] = {
                "id": transfer_id,
                "filename": safe_filename(original_name or source.name),
                "path": str(target),
                "size": target.stat().st_size,
                "sender_name": sender_name,
                "recipients": list(dict.fromkeys(recipients)),
                "downloaded_by": [],
                "created_at": time.time(),
            }
            self._write_json(self.queue_path, self.transfers)
        return transfer_id

    def queue_stream(self, source, size: int, recipients: List[str], sender_name: str, filename: str) -> str:
        transfer_id = uuid.uuid4().hex
        target = self.queue_files / f"{transfer_id}.bin"
        remaining = size
        with target.open("wb") as output:
            while remaining > 0:
                chunk = source.read(min(BUFFER_SIZE, remaining))
                if not chunk:
                    raise IOError("انقطع رفع الملف قبل اكتماله")
                output.write(chunk)
                remaining -= len(chunk)
        with self.lock:
            self.transfers[transfer_id] = {
                "id": transfer_id,
                "filename": safe_filename(filename),
                "path": str(target),
                "size": size,
                "sender_name": sender_name,
                "recipients": list(dict.fromkeys(recipients)),
                "downloaded_by": [],
                "created_at": time.time(),
            }
            self._write_json(self.queue_path, self.transfers)
        return transfer_id

    def inbox(self, device_id: str) -> List[Dict[str, Any]]:
        self.cleanup()
        with self.lock:
            values = []
            for transfer in self.transfers.values():
                if device_id in transfer.get("recipients", []) and device_id not in transfer.get("downloaded_by", []):
                    values.append({
                        "id": transfer["id"],
                        "filename": transfer["filename"],
                        "size": transfer["size"],
                        "sender_name": transfer["sender_name"],
                        "created_at": transfer["created_at"],
                    })
            return sorted(values, key=lambda item: item["created_at"], reverse=True)

    def get_transfer(self, transfer_id: str, device_id: str) -> Optional[Dict[str, Any]]:
        with self.lock:
            transfer = self.transfers.get(transfer_id)
            if not transfer or device_id not in transfer.get("recipients", []):
                return None
            return dict(transfer)

    def mark_downloaded(self, transfer_id: str, device_id: str) -> None:
        with self.lock:
            transfer = self.transfers.get(transfer_id)
            if not transfer:
                return
            downloaded = transfer.setdefault("downloaded_by", [])
            if device_id not in downloaded:
                downloaded.append(device_id)
            if set(downloaded) >= set(transfer.get("recipients", [])):
                try:
                    Path(transfer["path"]).unlink(missing_ok=True)
                except OSError:
                    pass
                self.transfers.pop(transfer_id, None)
            self._write_json(self.queue_path, self.transfers)

    def cleanup(self) -> None:
        cutoff = time.time() - QUEUE_TTL
        changed = False
        with self.lock:
            for transfer_id, transfer in list(self.transfers.items()):
                if float(transfer.get("created_at", 0)) < cutoff:
                    try:
                        Path(transfer.get("path", "")).unlink(missing_ok=True)
                    except OSError:
                        pass
                    self.transfers.pop(transfer_id, None)
                    changed = True
            if changed:
                self._write_json(self.queue_path, self.transfers)


class HubState:
    def __init__(self, store: PersistentStore, event_queue: "queue.Queue[Tuple[str, Any]]") -> None:
        self.store = store
        self.event_queue = event_queue
        self.clients: Dict[str, Dict[str, Any]] = {}
        self.lock = threading.RLock()

    def register(self, device_id: str, name: str, kind: str) -> Dict[str, Any]:
        with self.lock:
            self.clients[device_id] = {
                "id": device_id,
                "name": (name or "iPhone / Web")[:80],
                "type": (kind or "web")[:30],
                "last_seen": time.time(),
            }
            result = dict(self.clients[device_id])
        self.event_queue.put(("clients", None))
        return result

    def active_clients(self, excluding: str = "") -> List[Dict[str, Any]]:
        cutoff = time.time() - CLIENT_TIMEOUT
        with self.lock:
            for key, value in list(self.clients.items()):
                if value["last_seen"] < cutoff:
                    self.clients.pop(key, None)
            return sorted(
                [dict(value) for key, value in self.clients.items() if key != excluding],
                key=lambda item: item["name"].lower(),
            )


class TransferHandler(BaseHTTPRequestHandler):
    server_version = "SendViaLocalNet/2.0"

    @property
    def state(self) -> HubState:
        return self.server.state  # type: ignore[attr-defined]

    def log_message(self, fmt: str, *args: Any) -> None:
        return

    def _cors(self) -> None:
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-File-Name, X-File-Size")

    def _json(self, value: Any, status: int = 200) -> None:
        data = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self._cors()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _text(self, value: str, status: int = 200) -> None:
        data = value.encode("utf-8")
        self.send_response(status)
        self._cors()
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _serve_file(self, path: Path, content_type: Optional[str] = None, download_name: Optional[str] = None) -> None:
        if not path.is_file():
            self.send_error(404)
            return
        self.send_response(200)
        self._cors()
        self.send_header("Content-Type", content_type or mimetypes.guess_type(path.name)[0] or "application/octet-stream")
        self.send_header("Content-Length", str(path.stat().st_size))
        if download_name:
            encoded = urllib.parse.quote(download_name)
            self.send_header("Content-Disposition", f"attachment; filename*=UTF-8''{encoded}")
        self.end_headers()
        with path.open("rb") as source:
            shutil.copyfileobj(source, self.wfile, BUFFER_SIZE)

    def do_OPTIONS(self) -> None:
        self.send_response(204)
        self._cors()
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        query = urllib.parse.parse_qs(parsed.query)
        web_root = resource_path("web")
        if path in ("/", "/index.html"):
            self._serve_file(web_root / "index.html", "text/html; charset=utf-8")
        elif path == "/manifest.webmanifest":
            self._serve_file(web_root / "manifest.webmanifest", "application/manifest+json")
        elif path == "/sw.js":
            self._serve_file(web_root / "sw.js", "application/javascript; charset=utf-8")
        elif path == "/icon.svg":
            self._serve_file(web_root / "icon.svg", "image/svg+xml")
        elif path == "/upload":
            self._text(f"SVLN|{self.state.store.get_device_name()}|windows")
        elif path == "/api/info":
            self._json({
                "name": self.state.store.get_device_name(),
                "ip": local_ip(),
                "port": PORT,
                "version": "2.0.0",
            })
        elif path == "/api/devices":
            current_id = query.get("current_id", [""])[0]
            self._json({"devices": self.state.active_clients(current_id)})
        elif path.startswith("/api/inbox/"):
            device_id = urllib.parse.unquote(path.split("/", 3)[3])
            self._json({"files": self.state.store.inbox(device_id)})
        elif path.startswith("/api/download/"):
            parts = path.split("/")
            if len(parts) < 5:
                self.send_error(404)
                return
            transfer_id, device_id = parts[3], urllib.parse.unquote(parts[4])
            transfer = self.state.store.get_transfer(transfer_id, device_id)
            if not transfer:
                self.send_error(404)
                return
            self._serve_file(Path(transfer["path"]), download_name=transfer["filename"])
            self.state.store.mark_downloaded(transfer_id, device_id)
            self.state.event_queue.put(("log", f"تم تنزيل {transfer['filename']} بواسطة جهاز ويب"))
        else:
            self.send_error(404)

    def do_POST(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path in ("/api/register", "/api/heartbeat"):
            self._handle_registration()
        elif parsed.path == "/upload":
            self._handle_direct_upload()
        elif parsed.path == "/api/hub-upload":
            self._handle_hub_upload(parsed)
        else:
            self.send_error(404)

    def _read_json(self) -> Dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0") or 0)
        if length <= 0 or length > 1024 * 1024:
            return {}
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except Exception:
            return {}

    def _handle_registration(self) -> None:
        data = self._read_json()
        device_id = str(data.get("id") or "").strip() or uuid.uuid4().hex
        device = self.state.register(device_id, str(data.get("name") or "iPhone / Web"), str(data.get("type") or "web"))
        self._json({"ok": True, "device": device})

    def _handle_direct_upload(self) -> None:
        length = int(self.headers.get("Content-Length", "0") or 0)
        if length < 0:
            self._json({"ok": False, "error": "حجم الملف غير معروف"}, 411)
            return
        raw_name = self.headers.get("X-File-Name", "")
        filename = urllib.parse.unquote(raw_name) if raw_name else f"received_{int(time.time())}.bin"
        target = unique_path(downloads_dir(), filename)
        remaining = length
        try:
            with target.open("wb") as output:
                while remaining > 0:
                    chunk = self.rfile.read(min(BUFFER_SIZE, remaining))
                    if not chunk:
                        raise IOError("انقطع الإرسال")
                    output.write(chunk)
                    remaining -= len(chunk)
            self.state.event_queue.put(("received", str(target)))
            self._json({"ok": True, "filename": target.name, "size": length})
        except Exception as exc:
            target.unlink(missing_ok=True)
            self._json({"ok": False, "error": str(exc)}, 500)

    def _handle_hub_upload(self, parsed: urllib.parse.ParseResult) -> None:
        query = urllib.parse.parse_qs(parsed.query)
        recipients = [item for item in query.get("recipients", [""])[0].split(",") if item]
        filename = urllib.parse.unquote(query.get("filename", [f"file_{int(time.time())}.bin"])[0])
        sender_name = urllib.parse.unquote(query.get("sender_name", ["جهاز ويب"])[0])
        length = int(self.headers.get("Content-Length", "0") or 0)
        active_ids = {item["id"] for item in self.state.active_clients()}
        recipients = [item for item in recipients if item in active_ids]
        if not recipients:
            self._json({"ok": False, "error": "لا يوجد جهاز مستقبل متصل"}, 400)
            return
        try:
            transfer_id = self.state.store.queue_stream(self.rfile, length, recipients, sender_name, filename)
            self.state.event_queue.put(("log", f"تمت إضافة {filename} إلى صندوق {len(recipients)} جهاز"))
            self._json({"ok": True, "transfer_id": transfer_id, "recipient_count": len(recipients)})
        except Exception as exc:
            self._json({"ok": False, "error": str(exc)}, 500)


class TransferServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: Tuple[str, int], state: HubState):
        self.state = state
        super().__init__(address, TransferHandler)


class DesktopApp:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.store = PersistentStore()
        self.events: "queue.Queue[Tuple[str, Any]]" = queue.Queue()
        self.state = HubState(self.store, self.events)
        self.server: Optional[TransferServer] = None
        self.server_thread: Optional[threading.Thread] = None
        self.executor = concurrent.futures.ThreadPoolExecutor(max_workers=8)
        self.devices = self.store.get_devices()
        self.files: List[Path] = []
        self.direct_selected: Set[str] = {item.ip for item in self.devices if item.selected}
        self.web_selected: Set[str] = set()
        self.qr_photo = None

        self.root.title(DISPLAY_NAME)
        self.root.geometry("1180x760")
        self.root.minsize(930, 650)
        self.root.configure(bg="#F4F6FC")
        self._configure_style()
        self._build_ui()
        self._start_server()
        self._render_devices()
        self._refresh_web_clients()
        self.root.after(250, self._process_events)
        self.root.after(4000, self._periodic_refresh)
        self.root.protocol("WM_DELETE_WINDOW", self._close)

    def _configure_style(self) -> None:
        style = ttk.Style()
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass
        style.configure("TFrame", background="#F4F6FC")
        style.configure("Card.TFrame", background="#FFFFFF")
        style.configure("Title.TLabel", background="#FFFFFF", foreground="#101828", font=("Segoe UI", 16, "bold"))
        style.configure("Sub.TLabel", background="#FFFFFF", foreground="#667085", font=("Segoe UI", 10))
        style.configure("Hero.TLabel", background="#4338CA", foreground="#FFFFFF", font=("Segoe UI", 24, "bold"))
        style.configure("HeroSub.TLabel", background="#4338CA", foreground="#E0E7FF", font=("Segoe UI", 11))
        style.configure("Primary.TButton", font=("Segoe UI", 10, "bold"), padding=(14, 10))
        style.configure("Soft.TButton", font=("Segoe UI", 10, "bold"), padding=(12, 9))
        style.configure("Treeview", rowheight=32, font=("Segoe UI", 10))
        style.configure("Treeview.Heading", font=("Segoe UI", 10, "bold"))

    def _build_ui(self) -> None:
        container = ttk.Frame(self.root, padding=16)
        container.pack(fill="both", expand=True)

        hero = tk.Frame(container, bg="#4338CA", padx=22, pady=18)
        hero.pack(fill="x", pady=(0, 14))
        left = tk.Frame(hero, bg="#4338CA")
        left.pack(side="left", fill="both", expand=True)
        ttk.Label(left, text="نقل محلي Pro", style="Hero.TLabel").pack(anchor="w")
        ttk.Label(left, text="إرسال واستقبال بين Windows وAndroid وiPhone داخل الشبكة المحلية", style="HeroSub.TLabel").pack(anchor="w", pady=(3, 10))
        self.url_var = tk.StringVar(value="جاري تشغيل الاستقبال...")
        tk.Label(left, textvariable=self.url_var, bg="#4338CA", fg="white", font=("Segoe UI", 11, "bold")).pack(anchor="w")

        right = tk.Frame(hero, bg="#4338CA")
        right.pack(side="right", padx=(20, 0))
        self.qr_label = tk.Label(right, bg="white", width=118, height=118)
        self.qr_label.pack()
        ttk.Button(right, text="فتح نسخة الهاتف", command=self._open_web_app, style="Soft.TButton").pack(fill="x", pady=(8, 0))

        body = ttk.Panedwindow(container, orient="horizontal")
        body.pack(fill="both", expand=True)
        left_panel = ttk.Frame(body, style="Card.TFrame", padding=16)
        right_panel = ttk.Frame(body, style="Card.TFrame", padding=16)
        body.add(left_panel, weight=1)
        body.add(right_panel, weight=1)

        ttk.Label(left_panel, text="الأجهزة", style="Title.TLabel").pack(anchor="w")
        ttk.Label(left_panel, text="احفظ IP باسم اختياري أو اختر جهاز iPhone المتصل", style="Sub.TLabel").pack(anchor="w", pady=(2, 10))

        name_row = ttk.Frame(left_panel, style="Card.TFrame")
        name_row.pack(fill="x", pady=(0, 8))
        self.my_name_var = tk.StringVar(value=self.store.get_device_name())
        ttk.Entry(name_row, textvariable=self.my_name_var).pack(side="left", fill="x", expand=True)
        ttk.Button(name_row, text="حفظ اسم الكمبيوتر", command=self._save_computer_name, style="Soft.TButton").pack(side="left", padx=(8, 0))

        add_row = ttk.Frame(left_panel, style="Card.TFrame")
        add_row.pack(fill="x", pady=(0, 8))
        self.device_name_var = tk.StringVar()
        self.device_ip_var = tk.StringVar()
        ttk.Entry(add_row, textvariable=self.device_name_var).pack(side="left", fill="x", expand=True)
        ttk.Entry(add_row, textvariable=self.device_ip_var, width=18).pack(side="left", padx=8)
        self.device_name_var.set("اسم الجهاز")
        self.device_ip_var.set("192.168.1.20")
        ttk.Button(add_row, text="حفظ", command=self._add_device, style="Primary.TButton").pack(side="left")

        action_row = ttk.Frame(left_panel, style="Card.TFrame")
        action_row.pack(fill="x", pady=(0, 8))
        ttk.Button(action_row, text="بحث تلقائي", command=self._scan_network, style="Soft.TButton").pack(side="left")
        ttk.Button(action_row, text="تحديد الكل", command=self._select_all_targets, style="Soft.TButton").pack(side="left", padx=6)
        ttk.Button(action_row, text="حذف المحدد", command=self._delete_selected_direct, style="Soft.TButton").pack(side="left")

        self.targets = ttk.Treeview(left_panel, columns=("selected", "name", "kind", "address"), show="headings", selectmode="browse")
        self.targets.heading("selected", text="اختيار")
        self.targets.heading("name", text="الجهاز")
        self.targets.heading("kind", text="النوع")
        self.targets.heading("address", text="العنوان")
        self.targets.column("selected", width=62, anchor="center")
        self.targets.column("name", width=180)
        self.targets.column("kind", width=90, anchor="center")
        self.targets.column("address", width=150)
        self.targets.pack(fill="both", expand=True)
        self.targets.bind("<Double-1>", self._toggle_target)

        ttk.Label(right_panel, text="الملفات والإرسال", style="Title.TLabel").pack(anchor="w")
        ttk.Label(right_panel, text="اختر عدة ملفات ثم أرسلها إلى كل الأجهزة المحددة", style="Sub.TLabel").pack(anchor="w", pady=(2, 10))

        file_buttons = ttk.Frame(right_panel, style="Card.TFrame")
        file_buttons.pack(fill="x")
        ttk.Button(file_buttons, text="اختيار الملفات", command=self._choose_files, style="Primary.TButton").pack(side="left")
        ttk.Button(file_buttons, text="مسح القائمة", command=self._clear_files, style="Soft.TButton").pack(side="left", padx=8)
        ttk.Button(file_buttons, text="إرسال الآن", command=self._send, style="Primary.TButton").pack(side="right")

        self.file_list = tk.Listbox(right_panel, height=8, font=("Segoe UI", 10), bd=0, highlightthickness=1, highlightbackground="#E4E7EC")
        self.file_list.pack(fill="x", pady=10)

        self.progress = ttk.Progressbar(right_panel, mode="determinate", maximum=100)
        self.progress.pack(fill="x")
        self.status_var = tk.StringVar(value="جاهز. الاستقبال يعمل تلقائيًا.")
        ttk.Label(right_panel, textvariable=self.status_var, style="Sub.TLabel", wraplength=500).pack(anchor="w", pady=(8, 10))

        ttk.Label(right_panel, text="سجل النشاط", style="Title.TLabel").pack(anchor="w", pady=(4, 6))
        self.log_text = tk.Text(right_panel, height=12, font=("Segoe UI", 9), bg="#F8FAFC", fg="#344054", bd=0, padx=10, pady=10, state="disabled")
        self.log_text.pack(fill="both", expand=True)

    def _start_server(self) -> None:
        try:
            self.server = TransferServer(("0.0.0.0", PORT), self.state)
            self.server_thread = threading.Thread(target=self.server.serve_forever, name="svln-server", daemon=True)
            self.server_thread.start()
            url = f"http://{local_ip()}:{PORT}"
            self.url_var.set(url)
            self._make_qr(url)
            self._log(f"تم تشغيل الاستقبال: {url}")
        except OSError as exc:
            self.status_var.set(f"تعذر تشغيل المنفذ {PORT}: {exc}")
            self._log(f"خطأ تشغيل الاستقبال: {exc}")

    def _make_qr(self, url: str) -> None:
        if qrcode is None or ImageTk is None:
            self.qr_label.configure(text="QR", fg="#4338CA")
            return
        image = qrcode.make(url).resize((118, 118))
        self.qr_photo = ImageTk.PhotoImage(image)
        self.qr_label.configure(image=self.qr_photo, width=118, height=118)

    def _open_web_app(self) -> None:
        webbrowser.open(f"http://{local_ip()}:{PORT}")

    def _save_computer_name(self) -> None:
        self.store.set_device_name(self.my_name_var.get())
        self._log("تم حفظ اسم الكمبيوتر")

    @staticmethod
    def _valid_ip(ip: str) -> bool:
        try:
            socket.inet_aton(ip)
            return ip.count(".") == 3
        except OSError:
            return False

    def _add_device(self) -> None:
        name = self.device_name_var.get().strip()
        ip = self.device_ip_var.get().strip()
        if not self._valid_ip(ip):
            messagebox.showerror(DISPLAY_NAME, "عنوان IP غير صحيح")
            return
        if not name or name == "اسم الجهاز":
            name = f"جهاز {ip}"
        existing = next((item for item in self.devices if item.ip == ip), None)
        if existing:
            existing.name = name
            existing.selected = True
        else:
            self.devices.insert(0, DirectDevice(name, ip, True))
        self.direct_selected.add(ip)
        self.store.save_devices(self.devices)
        self._render_devices()

    def _render_devices(self) -> None:
        current_web = {item["id"]: item for item in self.state.active_clients()}
        for row in self.targets.get_children():
            self.targets.delete(row)
        for device in self.devices:
            chosen = device.ip in self.direct_selected
            self.targets.insert("", "end", iid=f"direct:{device.ip}", values=("☑" if chosen else "☐", device.name, "مباشر", f"{device.ip}:{PORT}"))
        for device_id, client in current_web.items():
            chosen = device_id in self.web_selected
            self.targets.insert("", "end", iid=f"web:{device_id}", values=("☑" if chosen else "☐", client["name"], "iPhone/Web", "متصل الآن"))

    def _toggle_target(self, _event=None) -> None:
        item = self.targets.focus()
        if not item:
            return
        kind, value = item.split(":", 1)
        target_set = self.direct_selected if kind == "direct" else self.web_selected
        if value in target_set:
            target_set.remove(value)
        else:
            target_set.add(value)
        for device in self.devices:
            device.selected = device.ip in self.direct_selected
        self.store.save_devices(self.devices)
        self._render_devices()

    def _select_all_targets(self) -> None:
        direct_all = {item.ip for item in self.devices}
        web_all = {item["id"] for item in self.state.active_clients()}
        if self.direct_selected >= direct_all and self.web_selected >= web_all and (direct_all or web_all):
            self.direct_selected.clear()
            self.web_selected.clear()
        else:
            self.direct_selected = direct_all
            self.web_selected = web_all
        self._render_devices()

    def _delete_selected_direct(self) -> None:
        if not self.direct_selected:
            return
        self.devices = [item for item in self.devices if item.ip not in self.direct_selected]
        self.direct_selected.clear()
        self.store.save_devices(self.devices)
        self._render_devices()

    def _choose_files(self) -> None:
        selected = filedialog.askopenfilenames(title="اختر الملفات")
        known = {str(item) for item in self.files}
        for value in selected:
            if value not in known:
                self.files.append(Path(value))
                known.add(value)
        self._render_files()

    def _clear_files(self) -> None:
        self.files.clear()
        self._render_files()

    def _render_files(self) -> None:
        self.file_list.delete(0, "end")
        for path in self.files:
            try:
                self.file_list.insert("end", f"{path.name}    ({format_size(path.stat().st_size)})")
            except OSError:
                self.file_list.insert("end", path.name)

    def _refresh_web_clients(self) -> None:
        active = {item["id"] for item in self.state.active_clients()}
        self.web_selected.intersection_update(active)
        self._render_devices()

    def _periodic_refresh(self) -> None:
        self._refresh_web_clients()
        self.root.after(4000, self._periodic_refresh)

    def _scan_network(self) -> None:
        ip = local_ip()
        if ip.count(".") != 3:
            messagebox.showerror(DISPLAY_NAME, "تعذر معرفة شبكة الكمبيوتر")
            return
        subnet = ip.rsplit(".", 1)[0]
        self.status_var.set(f"جاري البحث في {subnet}.x ...")
        self.executor.submit(self._scan_worker, subnet)

    def _scan_worker(self, subnet: str) -> None:
        def probe(host: str) -> Optional[Tuple[str, str]]:
            try:
                connection = http.client.HTTPConnection(host, PORT, timeout=0.65)
                connection.request("GET", "/upload")
                response = connection.getresponse()
                body = response.read(512).decode("utf-8", "replace")
                connection.close()
                if 200 <= response.status < 300 and body.startswith("SVLN|"):
                    parts = body.split("|")
                    return host, parts[1] if len(parts) > 1 else f"جهاز {host}"
            except Exception:
                return None
            return None

        hosts = [f"{subnet}.{index}" for index in range(1, 255)]
        found: List[Tuple[str, str]] = []
        with concurrent.futures.ThreadPoolExecutor(max_workers=32) as pool:
            for result in pool.map(probe, hosts):
                if result:
                    found.append(result)
        for host, name in found:
            existing = next((item for item in self.devices if item.ip == host), None)
            if existing:
                existing.name = name or existing.name
            else:
                self.devices.append(DirectDevice(name or f"جهاز {host}", host, True))
                self.direct_selected.add(host)
        self.store.save_devices(self.devices)
        self.events.put(("scan_done", len(found)))

    def _send(self) -> None:
        direct = [item for item in self.devices if item.ip in self.direct_selected]
        web_ids = [item["id"] for item in self.state.active_clients() if item["id"] in self.web_selected]
        if not direct and not web_ids:
            messagebox.showwarning(DISPLAY_NAME, "حدد جهازًا واحدًا على الأقل")
            return
        files = [item for item in self.files if item.is_file()]
        if not files:
            messagebox.showwarning(DISPLAY_NAME, "اختر ملفًا واحدًا على الأقل")
            return
        total = len(files) * (len(direct) + len(web_ids))
        self.progress["value"] = 0
        self.status_var.set(f"بدء {total} عملية إرسال...")
        self.executor.submit(self._send_worker, files, direct, web_ids, total)

    def _send_worker(self, files: List[Path], direct: List[DirectDevice], web_ids: List[str], total: int) -> None:
        jobs = []
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
            for path in files:
                for device in direct:
                    jobs.append(pool.submit(self._send_direct, path, device))
                for web_id in web_ids:
                    jobs.append(pool.submit(self._queue_for_web, path, web_id))
            ok = 0
            failed = 0
            completed = 0
            for future in concurrent.futures.as_completed(jobs):
                try:
                    success, message = future.result()
                except Exception as exc:
                    success, message = False, str(exc)
                ok += int(success)
                failed += int(not success)
                completed += 1
                self.events.put(("send_progress", (completed, total, ok, failed, message)))
        self.events.put(("send_done", (ok, failed)))

    def _send_direct(self, path: Path, device: DirectDevice) -> Tuple[bool, str]:
        connection = http.client.HTTPConnection(device.ip, PORT, timeout=120)
        size = path.stat().st_size
        headers = {
            "Content-Type": "application/octet-stream",
            "Content-Length": str(size),
            "X-File-Name": urllib.parse.quote(path.name),
            "X-File-Size": str(size),
        }
        try:
            connection.putrequest("POST", "/upload")
            for key, value in headers.items():
                connection.putheader(key, value)
            connection.endheaders()
            with path.open("rb") as source:
                while True:
                    chunk = source.read(BUFFER_SIZE)
                    if not chunk:
                        break
                    connection.send(chunk)
            response = connection.getresponse()
            response.read()
            success = 200 <= response.status < 300
            return success, (f"تم إرسال {path.name} إلى {device.name}" if success else f"فشل {path.name} إلى {device.name}: HTTP {response.status}")
        except Exception as exc:
            return False, f"فشل {path.name} إلى {device.name}: {exc}"
        finally:
            connection.close()

    def _queue_for_web(self, path: Path, device_id: str) -> Tuple[bool, str]:
        try:
            self.store.queue_file(path, [device_id], self.store.get_device_name())
            client = next((item for item in self.state.active_clients() if item["id"] == device_id), None)
            return True, f"تم إرسال {path.name} إلى {(client or {}).get('name', 'iPhone/Web')}"
        except Exception as exc:
            return False, f"فشل تجهيز {path.name}: {exc}"

    def _process_events(self) -> None:
        try:
            while True:
                kind, payload = self.events.get_nowait()
                if kind == "log":
                    self._log(str(payload))
                elif kind == "received":
                    path = Path(str(payload))
                    self._log(f"تم استقبال {path.name} وحفظه في {path.parent}")
                    self.status_var.set(f"تم استقبال: {path.name}")
                elif kind == "clients":
                    self._refresh_web_clients()
                elif kind == "scan_done":
                    self._render_devices()
                    self.status_var.set(f"انتهى البحث. تم العثور على {payload} جهاز.")
                    self._log(f"البحث التلقائي وجد {payload} جهاز")
                elif kind == "send_progress":
                    completed, total, ok, failed, message = payload
                    self.progress["value"] = int(completed * 100 / max(1, total))
                    self.status_var.set(f"اكتمل {completed}/{total} — نجح {ok}، فشل {failed}")
                    self._log(message)
                elif kind == "send_done":
                    ok, failed = payload
                    self.status_var.set(f"انتهى الإرسال — نجح {ok}، فشل {failed}")
        except queue.Empty:
            pass
        self.root.after(250, self._process_events)

    def _log(self, message: str) -> None:
        timestamp = time.strftime("%H:%M:%S")
        self.log_text.configure(state="normal")
        self.log_text.insert("1.0", f"[{timestamp}] {message}\n")
        self.log_text.configure(state="disabled")

    def _close(self) -> None:
        try:
            if self.server:
                self.server.shutdown()
                self.server.server_close()
        except Exception:
            pass
        self.executor.shutdown(wait=False, cancel_futures=True)
        self.root.destroy()


def main() -> None:
    root = tk.Tk()
    DesktopApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
