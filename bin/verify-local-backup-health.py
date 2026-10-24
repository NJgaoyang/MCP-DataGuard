#!/usr/bin/env python3
"""Read-only health gate for LOCAL encrypted PostgreSQL backups.

No deletion or cloud transfer. Every accepted archive must be a regular 0600
file with a matching private SHA256 manifest. Verify the newest backup's age
and the number of local copies. Emits no database data or credentials.
"""
import datetime as dt
import hashlib
import os
from pathlib import Path
import re
import stat
import sys

PATTERN = re.compile(
    r"^dataguard-[A-Za-z0-9_-]+-(\d{8}T\d{6}Z)-\d+\.dump\.gpg$"
)

def main() -> int:
    try:
        directory = Path(os.environ["DG_BACKUP_DIRECTORY"])
        max_hours = int(os.environ.get("DG_BACKUP_MAX_AGE_HOURS", "30"))
        min_copies = int(os.environ.get("DG_BACKUP_MIN_COPIES", "2"))
        if max_hours < 1 or max_hours > 720 or min_copies < 1 or min_copies > 30:
            raise ValueError("Backup age/copy thresholds outside safe bounds")
        if not directory.is_absolute() or directory.is_symlink():
            raise ValueError("Expected real absolute backup directory")
        root = directory.stat()
        if not stat.S_ISDIR(root.st_mode) or stat.S_IMODE(root.st_mode) != 0o700:
            raise ValueError("Backup directory must be private 0700")
        candidates = []
        for path in directory.iterdir():
            matched = PATTERN.fullmatch(path.name)
            if not matched:
                continue
            manifest = path.with_name(path.name + ".sha256")
            for target in (path, manifest):
                if target.is_symlink() or not target.is_file():
                    raise ValueError("Backup archive or checksum manifest is not a regular file")
                if stat.S_IMODE(target.stat().st_mode) != 0o600:
                    raise ValueError("Backup files must be private 0600")
            digest = hashlib.sha256()
            with path.open("rb") as content:
                for chunk in iter(lambda: content.read(1024 * 1024), b""):
                    digest.update(chunk)
            lines = manifest.read_text(encoding="utf-8").splitlines()
            if len(lines) != 1 or lines[0] != f"{digest.hexdigest()}  {path.name}":
                raise ValueError("Backup archive checksum mismatch")
            timestamp = dt.datetime.strptime(matched.group(1), "%Y%m%dT%H%M%SZ")
            created = timestamp.replace(tzinfo=dt.timezone.utc)
            if path.stat().st_size < 64:
                raise ValueError("Backup archive unexpectedly small")
            candidates.append(created)
        if len(candidates) < min_copies:
            raise ValueError("Insufficient verified encrypted backup copies")
        newest = max(candidates)
        hours = (dt.datetime.now(dt.timezone.utc) - newest).total_seconds() / 3600
        if hours < -0.25 or hours > max_hours:
            raise ValueError("Newest verified backup is stale or future-dated")
        print(f"LOCAL_ENCRYPTED_BACKUP_HEALTH_PASS verified={len(candidates)} newest_hours={hours:.2f}")
        return 0
    except (OSError, ValueError, KeyError, UnicodeError) as exc:
        # Fail closed without disclosing any file path or sensitive configuration.
        print("LOCAL_ENCRYPTED_BACKUP_HEALTH_FAIL: " + type(exc).__name__, file=sys.stderr)
        return 1

if __name__ == "__main__":
    sys.exit(main())
