#!/usr/bin/env python3
"""ADR compliance check, run in CI.

Verifies the health of ``docs/adr/`` without assuming a single template format
(the legacy 0001-0009 predate the adr-toolkit frontmatter, so this check is
deliberately lenient about their internal shape):

1. ADR numbers are contiguous starting at 0001 -- no gaps, no duplicates.
2. No unresolved ``{{token}}`` placeholders leaked into any ADR.
3. Every ADR file is linked from the index (docs/adr/README.md).

Exit code 0 on success; 1 (with messages on stderr) on any violation.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ADR_DIR = Path(__file__).resolve().parent.parent / "docs" / "adr"
ADR_RE = re.compile(r"^(\d{4})-[a-z0-9-]+\.md$")


def main() -> int:
    errors: list[str] = []

    adr_files = sorted(
        p for p in ADR_DIR.glob("*.md") if ADR_RE.match(p.name)
    )
    if not adr_files:
        print(f"error: no ADR files found in {ADR_DIR}", file=sys.stderr)
        return 1

    numbers = [int(ADR_RE.match(p.name).group(1)) for p in adr_files]

    # 1. Contiguous numbering from 0001.
    expected = list(range(1, len(numbers) + 1))
    if numbers != expected:
        errors.append(
            "ADR numbering is not contiguous from 0001: "
            f"found {numbers}, expected {expected}"
        )

    # 2. No unresolved template tokens.
    for p in adr_files:
        if "{{" in p.read_text(encoding="utf-8"):
            errors.append(f"{p.name}: contains an unresolved '{{{{...}}}}' token")

    # 3. Every ADR is linked from the index.
    readme = ADR_DIR / "README.md"
    if not readme.exists():
        errors.append("docs/adr/README.md (index) is missing")
    else:
        index_text = readme.read_text(encoding="utf-8")
        for p in adr_files:
            if p.name not in index_text:
                errors.append(f"{p.name}: not linked from docs/adr/README.md")

    if errors:
        print("ADR compliance check FAILED:", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        return 1

    print(f"ADR compliance check passed ({len(adr_files)} ADRs).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
