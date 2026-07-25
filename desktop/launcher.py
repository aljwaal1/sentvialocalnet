from __future__ import annotations

import os
import sys
import tkinter as tk

import app
from discovery_responder import DiscoveryResponder, config_name
from windows_runtime import RuntimeControl, enhance_windows_ui, notify_existing


def _show_window(root: tk.Tk) -> None:
    def show() -> None:
        try:
            root.deiconify()
            root.lift()
            root.attributes("-topmost", True)
            root.after(350, lambda: root.attributes("-topmost", False))
            root.focus_force()
        except tk.TclError:
            pass

    try:
        root.after(0, show)
    except tk.TclError:
        pass


def main() -> None:
    command = next((item.lower() for item in sys.argv[1:] if item.startswith("--")), "")
    if command == "--shutdown":
        notify_existing("SHUTDOWN")
        return
    if command == "--show":
        notify_existing("SHOW")
        return

    control = RuntimeControl()
    if not control.start():
        notify_existing("SHOW")
        return

    enhance_windows_ui(app)
    root = tk.Tk()
    desktop = app.DesktopApp(root)
    responder = DiscoveryResponder(config_name, app.local_ip)

    def shutdown() -> None:
        try:
            root.after(0, desktop._close)
        except tk.TclError:
            pass

    control.set_handlers(lambda: _show_window(root), shutdown)
    responder.start()
    try:
        root.mainloop()
    finally:
        responder.stop()
        control.stop()

    # ThreadPoolExecutor may still contain a network task. Exiting here guarantees
    # that Windows releases the EXE immediately so an upgrade can replace it.
    os._exit(0)


if __name__ == "__main__":
    main()
