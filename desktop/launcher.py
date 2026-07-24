from __future__ import annotations

import app
from discovery_responder import DiscoveryResponder, config_name


def main() -> None:
    responder = DiscoveryResponder(config_name, app.local_ip)
    responder.start()
    try:
        app.main()
    finally:
        responder.stop()


if __name__ == "__main__":
    main()
