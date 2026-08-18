#!/usr/bin/env python3
"""Flyway migration compliance check, run in CI.

Flyway migrations live in two source directories, split by Gradle's source-set
convention rather than by Flyway itself:

- ``src/main/resources/db/migration/`` -- the ``.sql`` migrations.
- ``src/main/java/db/migration/`` -- the ``.java`` migrations (V13, V19 as of #185).

At runtime this is invisible: Gradle puts both output roots on the classpath, so
Flyway's single ``classpath:db/migration`` location sees them merged. The hazard is at
authoring time -- ``ls src/main/resources/db/migration | sort -V | tail -1`` reports a
stale "latest" version and silently misses the Java ones, inviting a colliding version
number that Flyway rejects only at boot (see #177, and ADR-0009 / ADR-0020).

Verifies, across BOTH directories:

1. No duplicate version numbers.
2. Versions are contiguous starting at V1 -- no gaps.
3. Every file matches ``V<n>__snake_case_description`` (``.sql`` or ``.java``).
4. Prints the true latest version, so it can be used to pick the next one.

Versions are numeric and are sorted numerically throughout -- never lexically, which is
exactly the trap that makes ``ls | sort`` (without ``-V``) put V10 before V9.

Exit code 0 on success; 1 (with messages on stderr) on any violation.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SQL_MIGRATION_DIR = REPO_ROOT / "src" / "main" / "resources" / "db" / "migration"
JAVA_MIGRATION_DIR = REPO_ROOT / "src" / "main" / "java" / "db" / "migration"
MIGRATION_DIRS = (SQL_MIGRATION_DIR, JAVA_MIGRATION_DIR)
MIGRATION_RE = re.compile(r"^V(\d+)__[a-z0-9_]+\.(sql|java)$")


def main() -> int:
    errors: list[str] = []

    missing_dirs = [d for d in MIGRATION_DIRS if not d.is_dir()]
    if missing_dirs:
        for d in missing_dirs:
            errors.append(f"missing migration directory: {d.relative_to(REPO_ROOT)}")
        print("Migration compliance check FAILED:", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        return 1

    all_files = sorted(
        p for d in MIGRATION_DIRS for p in d.iterdir() if p.is_file()
    )

    # 3. Naming convention -- checked first since a bad name has no version to sort by.
    valid: list[tuple[int, Path]] = []
    for p in all_files:
        m = MIGRATION_RE.match(p.name)
        if not m:
            errors.append(
                f"{p.relative_to(REPO_ROOT)}: does not match "
                "V<n>__snake_case_description.(sql|java)"
            )
        else:
            valid.append((int(m.group(1)), p))

    if not valid:
        print("error: no migration files found in either migration directory", file=sys.stderr)
        return 1

    # 1. No duplicate version numbers, across both trees.
    by_version: dict[int, list[Path]] = {}
    for version, p in valid:
        by_version.setdefault(version, []).append(p)

    for version in sorted(by_version):
        paths = by_version[version]
        if len(paths) > 1:
            names = ", ".join(str(p.relative_to(REPO_ROOT)) for p in paths)
            errors.append(f"V{version} is used by more than one file: {names}")

    # 2. Contiguous versions starting at V1 -- numeric sort, not lexical (V10 vs V9).
    versions = sorted(by_version)
    expected = set(range(1, versions[-1] + 1))
    missing = sorted(expected - set(versions))
    if missing:
        errors.append(
            "migration versions are not contiguous: missing "
            + ", ".join(f"V{v}" for v in missing)
        )

    if errors:
        print("Migration compliance check FAILED:", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        return 1

    latest = versions[-1]
    print(
        f"Migration compliance check passed ({len(valid)} migrations across "
        f"{len(MIGRATION_DIRS)} directories). Latest version: V{latest}."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
