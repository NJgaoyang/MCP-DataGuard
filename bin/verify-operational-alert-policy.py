#!/usr/bin/env python3
"""No external HTTP/DB dependencies: fail-closed notifier integration policy."""
import contextlib
import importlib.util
import json
import os
from pathlib import Path
import stat
import tempfile
from unittest import mock

path=Path(__file__).with_name("notify-operational-incidents.py")
spec=importlib.util.spec_from_file_location("dg_ops_notifier",path)
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class Response:
    status=204
    def __enter__(self):return self
    def __exit__(self,*args):pass

with tempfile.TemporaryDirectory() as temp:
    root=Path(temp)
    root.chmod(0o700)
    key=root/"secret.key"
    key.write_bytes(b"isolated-only-test-hmac-key-32-bytes-123456")
    key.chmod(0o600)
    state=root/"state"
    state.mkdir(mode=0o700)
    env={
      "DG_ALERT_WEBHOOK_URL":"https://security.example.invalid/v1/events",
      "DG_ALERT_WEBHOOK_HOST":"security.example.invalid",
      "DG_ALERT_WEBHOOK_HMAC_FILE":str(key),
      "DG_ALERT_STATE_DIRECTORY":str(state)
    }
    sent=[]
    counts={"unknownWrites":0,"criticalAlerts":0,"deadLetters":0}
    def sender(request,timeout):
        assert timeout<=8 and request.get_method()=="POST"
        body=json.loads(request.data)
        import hashlib,hmac
        header=request.get_header("X-dataguard-signature-sha256")
        assert header=="sha256="+hmac.new(key.read_bytes(),request.data,hashlib.sha256).hexdigest()
        sent.append(body)
        return Response()
    with mock.patch.dict(os.environ,env,clear=False), \
      mock.patch.object(module.socket,"getaddrinfo",
        return_value=[(2,1,6,"",("8.8.8.8",443))]), \
      mock.patch.object(module,"read_counts",side_effect=lambda:counts), \
      mock.patch.object(module.urllib.request,"urlopen",side_effect=sender):
        assert module.main()==0 and not sent
        print("PASS: zero incidents does not send notification")
        counts["unknownWrites"]=1
        assert module.main()==0 and len(sent)==1 and sent[0]["event"]=="OPEN"
        assert "sql" not in json.dumps(sent).lower()
        print("PASS: signed incident sends only aggregate counts")
        assert module.main()==0 and len(sent)==1
        print("PASS: duplicate state does not trigger notification flood")
        counts["unknownWrites"]=0
        assert module.main()==0 and len(sent)==2 and sent[1]["event"]=="RECOVERED"
        print("PASS: recovery notification emitted after incident clears")
        with mock.patch.dict(os.environ,{"DG_ALERT_WEBHOOK_URL":"http://localhost/"}):
            assert module.main()!=0 and len(sent)==2
        print("PASS: HTTP/loopback webhook rejected")
        counts["deadLetters"]=1
        with mock.patch.object(module.urllib.request,"urlopen",side_effect=TimeoutError):
            assert module.main()!=0
        assert module.main()==0 and len(sent)==3
        print("PASS: failed delivery is retried without advancing dedup state")
print("OPERATIONAL_ALERT_NOTIFICATION_POLICY_PASS")
