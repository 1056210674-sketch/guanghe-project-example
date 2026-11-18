"""Export the exact FastAPI runtime schema as the reviewed static contract."""

from pathlib import Path

import yaml

from app.api.application import create_app
from app.common.settings import AppSettings

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "contracts" / "agent-openapi.yaml"


def main() -> None:
    schema = create_app(AppSettings()).openapi()
    rendered = yaml.safe_dump(schema, allow_unicode=True, sort_keys=False, width=100)
    TARGET.write_text(rendered, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
