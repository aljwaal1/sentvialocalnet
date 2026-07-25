from __future__ import annotations

import socket
import threading
import tkinter as tk
from typing import Callable, Optional

CONTROL_HOST = "127.0.0.1"
CONTROL_PORT = 5053
APP_VERSION = "2.2.0"


class RuntimeControl:
    """Local-only control channel used for single instance and safe upgrades."""

    def __init__(self) -> None:
        self._socket: Optional[socket.socket] = None
        self._thread: Optional[threading.Thread] = None
        self._running = False
        self._on_show: Optional[Callable[[], None]] = None
        self._on_shutdown: Optional[Callable[[], None]] = None

    def set_handlers(self, on_show: Callable[[], None], on_shutdown: Callable[[], None]) -> None:
        self._on_show = on_show
        self._on_shutdown = on_shutdown

    def start(self) -> bool:
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            server.bind((CONTROL_HOST, CONTROL_PORT))
            server.listen(4)
            server.settimeout(1.0)
        except OSError:
            server.close()
            return False

        self._socket = server
        self._running = True
        self._thread = threading.Thread(target=self._serve, name="svln-runtime-control", daemon=True)
        self._thread.start()
        return True

    def stop(self) -> None:
        self._running = False
        if self._socket is not None:
            try:
                self._socket.close()
            except OSError:
                pass
        self._socket = None

    def _serve(self) -> None:
        while self._running and self._socket is not None:
            try:
                client, _ = self._socket.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            try:
                client.settimeout(1.0)
                command = client.recv(128).decode("utf-8", "ignore").strip().upper()
                if command == "SHUTDOWN" and self._on_shutdown is not None:
                    self._on_shutdown()
                    client.sendall(b"OK\n")
                elif command == "SHOW" and self._on_show is not None:
                    self._on_show()
                    client.sendall(b"OK\n")
                else:
                    client.sendall(b"UNKNOWN\n")
            except OSError:
                pass
            finally:
                try:
                    client.close()
                except OSError:
                    pass


def notify_existing(command: str, timeout: float = 1.5) -> bool:
    try:
        with socket.create_connection((CONTROL_HOST, CONTROL_PORT), timeout=timeout) as client:
            client.sendall((command.strip().upper() + "\n").encode("utf-8"))
            client.settimeout(timeout)
            return client.recv(32).startswith(b"OK")
    except OSError:
        return False


def enhance_windows_ui(app_module) -> None:
    """Patch the existing UI and web page without replacing transfer logic."""

    original_build = app_module.DesktopApp._build_ui
    original_start_server = app_module.DesktopApp._start_server
    original_periodic_refresh = app_module.DesktopApp._periodic_refresh
    original_serve_file = app_module.TransferHandler._serve_file

    def refresh_ip(self) -> None:
        ip = app_module.local_ip()
        address = f"{ip}:{app_module.PORT}"
        url = f"http://{address}"
        if hasattr(self, "current_ip_var"):
            self.current_ip_var.set(f"IP الحالي: {address}")
        if hasattr(self, "url_var"):
            self.url_var.set(f"الرابط المحلي: {url}")
        previous = getattr(self, "_displayed_local_url", "")
        if previous != url:
            self._displayed_local_url = url
            if getattr(self, "server", None):
                try:
                    self._make_qr(url)
                except Exception:
                    pass

    def copy_ip(self) -> None:
        value = f"{app_module.local_ip()}:{app_module.PORT}"
        self.root.clipboard_clear()
        self.root.clipboard_append(value)
        self.root.update_idletasks()
        try:
            self.status_var.set(f"تم نسخ IP الحالي: {value}")
        except Exception:
            pass

    def build_ui(self) -> None:
        original_build(self)
        self.root.title(f"{app_module.DISPLAY_NAME} — {APP_VERSION}")
        self.current_ip_var = tk.StringVar(value="جاري تحديد IP الحالي...")
        try:
            container = self.root.winfo_children()[0]
            hero = container.winfo_children()[0]
            left = hero.winfo_children()[0]
            ip_row = tk.Frame(left, bg="#4338CA")
            ip_row.pack(anchor="w", pady=(7, 0), fill="x")
            tk.Label(
                ip_row,
                textvariable=self.current_ip_var,
                bg="#4338CA",
                fg="#E0E7FF",
                font=("Segoe UI", 10, "bold"),
            ).pack(side="left")
            tk.Button(
                ip_row,
                text="نسخ IP",
                command=lambda: copy_ip(self),
                bg="#EEF2FF",
                fg="#3730A3",
                activebackground="#FFFFFF",
                activeforeground="#312E81",
                bd=0,
                relief="flat",
                padx=10,
                pady=4,
                cursor="hand2",
                font=("Segoe UI", 9, "bold"),
            ).pack(side="left", padx=(10, 0))
        except Exception:
            pass
        refresh_ip(self)

    def start_server(self) -> None:
        original_start_server(self)
        refresh_ip(self)

    def periodic_refresh(self) -> None:
        refresh_ip(self)
        original_periodic_refresh(self)

    def serve_file(self, path, content_type=None, download_name=None) -> None:
        if path.name.lower() != "index.html":
            original_serve_file(self, path, content_type, download_name)
            return
        try:
            html = path.read_text(encoding="utf-8")
            marker = "svln-current-server-ip"
            if marker not in html:
                injection = r'''
<script id="svln-current-server-ip">
(async function(){
  try {
    const response = await fetch('/api/info', {cache:'no-store'});
    const info = await response.json();
    const hero = document.querySelector('.hero');
    if (!hero) return;
    const box = document.createElement('div');
    box.style.cssText = 'margin-top:12px;padding:10px 12px;border-radius:14px;background:rgba(255,255,255,.16);border:1px solid rgba(255,255,255,.25);font-weight:800;direction:ltr;text-align:center;cursor:pointer';
    box.textContent = 'Windows IP: ' + info.ip + ':' + info.port + '  •  اضغط للنسخ';
    box.onclick = function(){
      const value = info.ip + ':' + info.port;
      if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(value).catch(function(){});
      const status = document.getElementById('status');
      if (status) status.textContent = 'تم نسخ عنوان الكمبيوتر: ' + value;
    };
    hero.appendChild(box);
  } catch (error) {}
})();
</script>
'''
                html = html.replace("</body>", injection + "</body>")
            data = html.encode("utf-8")
            self.send_response(200)
            self._cors()
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Cache-Control", "no-store, max-age=0")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except Exception:
            original_serve_file(self, path, content_type, download_name)

    app_module.DesktopApp._build_ui = build_ui
    app_module.DesktopApp._start_server = start_server
    app_module.DesktopApp._periodic_refresh = periodic_refresh
    app_module.TransferHandler._serve_file = serve_file
