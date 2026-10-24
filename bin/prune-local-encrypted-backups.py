#!/usr/bin/env python3
"""Conservative local encrypted-backup retention.

Default is dry-run. With explicit DG_BACKUP_PRUNE_APPLY=yes, delete only
well-formed archives and their matching manifests older than retention, while
preserving the two most recent validated copies. Never follow symlinks,
delete unknown files, or run concurrently with a streaming backup.
"""
import datetime as dt
import fcntl
import hashlib
import os
from pathlib import Path
import re
import stat
import sys

PATTERN = re.compile(r"^dataguard-[A-Za-z0-9_-]+-(\d{8}T\d{6}Z)-\d+\.dump\.gpg$")

def main():
    try:
        path=Path(os.environ["DG_BACKUP_DIRECTORY"])
        retention=int(os.environ.get("DG_BACKUP_RETENTION_DAYS","30"))
        minimum=int(os.environ.get("DG_BACKUP_MIN_COPIES","2"))
        if not 7<=retention<=3650 or not 2<=minimum<=30:
            raise ValueError("Unsafe retention/minimum bounds")
        if not path.is_absolute() or path.is_symlink() or not path.is_dir():
            raise ValueError("Unsafe backup directory")
        if stat.S_IMODE(path.stat().st_mode)!=0o700:
            raise ValueError("Directory must be 0700")
        lock=path/".backup.lock"
        with lock.open("a+b") as handle:
            os.chmod(lock,0o600)
            fcntl.flock(handle.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB)
            entries=[]
            for file in path.iterdir():
                match=PATTERN.fullmatch(file.name)
                if not match:
                    continue
                manifest=file.with_name(file.name+".sha256")
                for target in (file,manifest):
                    if target.is_symlink() or not target.is_file() or stat.S_IMODE(target.stat().st_mode)!=0o600:
                        raise ValueError("Invalid archive/manifest type or mode")
                data=hashlib.sha256()
                with file.open("rb") as fd:
                    for block in iter(lambda:fd.read(1024*1024),b""):
                        data.update(block)
                if manifest.read_text().strip()!=data.hexdigest()+"  "+file.name:
                    raise ValueError("Checksum mismatch")
                age=dt.datetime.strptime(match.group(1),"%Y%m%dT%H%M%SZ").replace(tzinfo=dt.timezone.utc)
                entries.append((age,file,manifest))
            entries.sort(reverse=True)
            if len(entries)<minimum:
                raise ValueError("Not enough verified backups to apply retention")
            now=dt.datetime.now(dt.timezone.utc)
            cutoff=now-dt.timedelta(days=retention)
            stale=[(file,manifest) for i,(age,file,manifest) in enumerate(entries)
                   if i>=minimum and age<cutoff]
            enabled=os.getenv("DG_BACKUP_PRUNE_APPLY")=="yes"
            if enabled:
                for archive,manifest in stale:
                    # unlink verified files only; never recurse into user directories.
                    archive.unlink()
                    manifest.unlink()
            status="APPLIED" if enabled else "DRY_RUN"
            print(f"LOCAL_BACKUP_RETENTION_{status} candidates={len(stale)} preserved={len(entries)-len(stale)}")
            return 0
    except (OSError,KeyError,ValueError,UnicodeError,BlockingIOError) as error:
        print("LOCAL_BACKUP_RETENTION_FAIL: "+type(error).__name__,file=sys.stderr)
        return 1

if __name__=="__main__":
    sys.exit(main())
