#!/usr/bin/env python3
"""Test the local backup gate using isolated synthetic encrypted-file placeholders."""
import datetime as dt, hashlib, os, pathlib, subprocess, sys, tempfile

SCRIPT=pathlib.Path(__file__).with_name("verify-local-backup-health.py")

def run(path, expected):
    env = dict(os.environ, DG_BACKUP_DIRECTORY=str(path),
               DG_BACKUP_MAX_AGE_HOURS="24", DG_BACKUP_MIN_COPIES="2")
    result = subprocess.run([sys.executable,str(SCRIPT)], env=env,
                            capture_output=True,text=True,timeout=10)
    if (result.returncode==0) != expected:
        raise AssertionError(f"Expected {expected}, got {result.returncode}")

with tempfile.TemporaryDirectory() as temp:
    root=pathlib.Path(temp)
    root.chmod(0o700)
    now=dt.datetime.now(dt.timezone.utc)
    files=[]
    for i in range(2):
        stamp=(now-dt.timedelta(hours=i)).strftime("%Y%m%dT%H%M%SZ")
        path=root/f"dataguard-dataguard-{stamp}-{i+100}.dump.gpg"
        body=(b"synthetic-encrypted-test-only-contents"+bytes([i]))*20
        path.write_bytes(body);path.chmod(0o600)
        manifest=path.with_name(path.name+".sha256")
        manifest.write_text(hashlib.sha256(body).hexdigest()+"  "+path.name+"\n")
        manifest.chmod(0o600)
        files.append(path)
    run(root,True)
    print("PASS: two private fresh archives with matching SHA256")
    files[0].write_bytes(files[0].read_bytes()+b"tampered")
    run(root,False)
    print("PASS: modified archive rejected")
    original=(b"synthetic-encrypted-test-only-contents"+bytes([0]))*20
    files[0].write_bytes(original)
    run(root,True)
    files[1].with_name(files[1].name+".sha256").unlink()
    run(root,False)
    print("PASS: missing manifest rejected")
    manifest=files[1].with_name(files[1].name+".sha256")
    manifest.write_text(hashlib.sha256(files[1].read_bytes()).hexdigest()+
                        "  "+files[1].name+"\n")
    manifest.chmod(0o600)
    files[1].chmod(0o644)
    run(root,False)
    print("PASS: world-readable encrypted archive rejected")
    files[1].chmod(0o600)
    root.chmod(0o755)
    run(root,False)
    print("PASS: non-private archive directory rejected")
print("LOCAL_BACKUP_HEALTH_POLICY_PASS")
