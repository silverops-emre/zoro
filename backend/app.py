"""Zoro 2.0 entry point.

gunicorn:  gunicorn --workers 2 --threads 4 --bind 127.0.0.1:5050 app:app
local:     python app.py
"""

import os
from pathlib import Path

from dotenv import load_dotenv

load_dotenv(Path(__file__).resolve().parent / ".env")

from zoro.web import create_app  # noqa: E402  (needs .env loaded first)

app = create_app()

if __name__ == "__main__":
    host = os.environ.get("ZORO_HOST", "127.0.0.1")
    port = int(os.environ.get("ZORO_PORT", "5050"))
    print(f"ZORO 2.0 SUNUCUSU http://{host}:{port}")
    app.run(host=host, port=port, debug=False)
