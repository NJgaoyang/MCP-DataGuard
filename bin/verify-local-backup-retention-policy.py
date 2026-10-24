#!/usr/bin/env python3
"""Retention must preserve verified newest copies and refuse unsafe input."""
import datetime as dt
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tempfile

script=Path(__file__).with_name("prune-local-encrypted-backups.py")
with tempfile.TemporaryDirectory() as temp:
    root=Path(temp);root.chmod(0o700)
    paths=[]
    for i,days in enumerate((0,1,45,60)):
        created=(dt.datetime.now(dt.timezone.utc)-dt.timedelta(days=days))
        name=f"dataguard-dataguard-{created.strftime('%Y%m%dT%H%M%SZ')}-{i+1}.dump.gpg"
        archive=root/name
        content=(b"encrypted-test-placeholder-"+bytes([i]))*40
        archive.write_bytes(content);archive.chmod(0o600)
        manifest=root/(name+".sha256")
        manifest.write_text(hashlib.sha256(content).hexdigest()+"  "+name+"\n")
        manifest.chmod(0o600)
        paths.append(archive)
    env=dict(os.environ,DG_BACKUP_DIRECTORY=str(root),
             DG_BACKUP_MIN_COPIES="2",DG_BACKUP_RETENTION_DAYS="30")
    def execute(**overrides):
        return subprocess.run([sys.executable,str(script)],env={**env,**overrides},
                              capture_output=True,text=True,timeout=10)
    result=execute()
    assert result.returncode==0 and "DRY_RUN candidates=2" in result.stdout
    assert all(p.exists() for p in paths)
    print("PASS: default retention is dry-run only")
    result=execute(DG_BACKUP_PRUNE_APPLY="yes")
    assert result.returncode==0 and "APPLIED candidates=2" in result.stdout
    assert all(p.exists() for p in paths[:2])
    assert all(not p.exists() for p in paths[2:])
    print("PASS: two newest verified backups preserved")
    paths[0].write_bytes(paths[0].read_bytes()+b"tampered")
    assert execute(DG_BACKUP_PRUNE_APPLY="yes").returncode!=0
    assert paths[1].exists()
    print("PASS: corrupted backup aborts retention without deleting healthy copy")
print("LOCAL_BACKUP_RETENTION_POLICY_PASS")
