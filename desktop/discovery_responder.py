from __future__ import annotations

import json
import os
import socket
import threading
import uuid
from pathlib import Path
from typing import Callable, Optional

DISCOVERY_PORT = 5052
TRANSFER_PORT = 5051
DISCOVER_PREFIX = b"SVLN_DISCOVER|"


class DiscoveryResponder:
    def __init__(self, name_provider: Callable[[], str], ip_provider: Callable[[], str]) -> None:
        self.name_provider = name_provider
        self.ip_provider = ip_provider
        self._running = False
        self._socket: Optional[socket.socket] = None
        self._thread: Optional[threading.Thread] = None

    def start(self) -> None:
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(target=self._run, name="svln-discovery", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._running = False
        if self._socket is not None:
            try:
                self._socket.close()
            except OSError:
                pass
        self._socket = None

    def _run(self) -> None:
        sock: Optional[socket.socket] = None
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.bind(("", DISCOVERY_PORT))
            sock.settimeout(1.0)
            self._socket = sock
            while self._running:
                try:
                    data, sender = sock.recvfrom(2048)
                except socket.timeout:
                    continue
                except OSError:
                    break
                if not data.startswith(DISCOVER_PREFIX):
                    continue
                name = _clean(self.name_provider() or socket.gethostname() or "كمبيوتر Windows")
                ip = self.ip_provider()
                if not ip or ip.startswith("127."):
                    continue
                reply = f"SVLN_DEVICE|{name}|windows|{ip}|{TRANSFER_PORT}|{config_id()}".encode("utf-8")
                try:
                    sock.sendto(reply, sender)
                except OSError:
                    continue
        finally:
            if sock is not None:
                try:
                    sock.close()
                except OSError:
                    pass
            self._socket = None
            self._running = False


def _config_path() -> Path:
    root = Path(os.getenv("APPDATA") or (Path.home() / ".config")) / "SendViaLocalNet"
    root.mkdir(parents=True, exist_ok=True)
    return root / "config.json"


def _read_config() -> dict:
    path = _config_path()
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}


def _write_config(data: dict) -> None:
    path = _config_path()
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(path)


def config_id() -> str:
    data = _read_config()
    value = str(data.get("device_id") or "").strip()
    if value:
        return value
    value = "windows-" + str(uuid.uuid4())
    data["device_id"] = value
    try:
        _write_config(data)
    except Exception:
        pass
    return value


def config_name() -> str:
    data = _read_config()
    value = str(data.get("device_name") or "").strip()
    if value:
        return value
    return socket.gethostname() or "كمبيوتر Windows"


def _clean(value: str) -> str:
    cleaned = value.replace("|", " ").replace("\r", " ").replace("\n", " ").strip()
    return cleaned[:80] or "كمبيوتر Windows"
