#!/usr/bin/env python3
"""Real HTTPS roundtrip, signed payload, recovery, redirect and retry test.

No external network. Only an ephemeral TLS server on 127.0.0.1 with an
ephemeral CA test certificate. The notifier still validates the expected
public DNS address and TLS hostname, while test-only socket routing replaces
the outbound connection with the isolated local server. Never touch prod.
"""
import hashlib
import hmac
import importlib.util
import json
import os
from pathlib import Path
import socket
import ssl
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from unittest import mock

SOURCE=Path(__file__).with_name("notify-operational-incidents.py")
spec=importlib.util.spec_from_file_location("dg_alert_real_https",SOURCE)
mod=importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

with tempfile.TemporaryDirectory(prefix="dg-tls-notification-") as temp:
    base=Path(temp);base.chmod(0o700)
    certificate=base/"root.pem"
    private=base/"server.key"
    subprocess.run(["openssl","req","-x509","-newkey","rsa:2048",
        "-nodes","-days","1","-keyout",str(private),"-out",str(certificate),
        "-subj","/CN=alerts.integration.invalid",
        "-addext","subjectAltName=DNS:alerts.integration.invalid"],
        stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,
        timeout=18,check=True)
    private.chmod(0o600)
    secret=base/"webhook.key"
    secret.write_bytes(os.urandom(48));secret.chmod(0o600)
    state=base/"state";state.mkdir(mode=0o700)
    incidents=[]
    failures=[]

    class Receiver(BaseHTTPRequestHandler):
        def do_POST(self):
            raw=self.rfile.read(int(self.headers.get("Content-Length","0")))
            expected="sha256="+hmac.new(secret.read_bytes(),raw,hashlib.sha256).hexdigest()
            if (self.path!="/incident" or not
                hmac.compare_digest(self.headers.get("X-DataGuard-Signature-SHA256",""),expected)):
                failures.append("invalid payload/authentication")
                self.send_error(403);return
            item=json.loads(raw)
            assert item["fingerprint"]==self.headers.get("X-DataGuard-Event-Id")
            assert "sql" not in raw.decode().lower()
            incidents.append(item)
            status=self.server.next_status
            self.server.next_status=202
            self.send_response(status)
            if status==302:self.send_header("Location","https://localhost/private")
            self.send_header("Content-Length","0")
            self.end_headers()
        def log_message(self,*args):
            pass

    server=ThreadingHTTPServer(("127.0.0.1",0),Receiver)
    server.next_status=202
    server_port=server.server_address[1]
    context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(str(certificate),str(private))
    server.socket=context.wrap_socket(server.socket,server_side=True)
    thread=threading.Thread(target=server.serve_forever,daemon=True)
    thread.start()
    counts={"unknownWrites":0,"criticalAlerts":0,"deadLetters":0}
    cfg={
      "DG_ALERT_WEBHOOK_URL":"https://alerts.integration.invalid/incident",
      "DG_ALERT_WEBHOOK_HOST":"alerts.integration.invalid",
      "DG_ALERT_WEBHOOK_HMAC_FILE":str(secret),
      "DG_ALERT_STATE_DIRECTORY":str(state),
      "SSL_CERT_FILE":str(certificate),
      # urllib's DNS-pinned sender deliberately ignores environment proxies.
      "HTTPS_PROXY":"http://127.0.0.1:9",
    }
    def rerouted_tcp(address,timeout=None,source_address=None,**kwargs):
        # The actual network connection is local only; no outside IP traffic.
        assert address==("8.8.8.8",443), address
        sock=socket.socket(socket.AF_INET,socket.SOCK_STREAM)
        sock.settimeout(timeout if timeout is not None else 8)
        sock.connect(("127.0.0.1",server_port))
        return sock
    try:
        with mock.patch.dict(os.environ,cfg,clear=False), \
             mock.patch.object(mod.socket,"getaddrinfo",
              return_value=[(socket.AF_INET,socket.SOCK_STREAM,6,"",("8.8.8.8",443))]), \
             mock.patch.object(mod.socket,"create_connection",side_effect=rerouted_tcp), \
             mock.patch.object(mod,"read_counts",side_effect=lambda:counts):
            assert mod.main()==0 and not incidents
            counts["unknownWrites"]=1
            assert mod.main()==0 and len(incidents)==1
            assert incidents[-1]["event"]=="OPEN"
            print("PASS: real CA-verified HTTPS POST, signed aggregate incident")
            assert mod.main()==0 and len(incidents)==1
            print("PASS: persisted incident fingerprint prevents duplicate delivery")
            counts["unknownWrites"]=0
            assert mod.main()==0 and incidents[-1]["event"]=="RECOVERED"
            print("PASS: real TLS recovery notification")
            counts["criticalAlerts"]=2
            server.next_status=503
            assert mod.main()!=0
            before=len(incidents)
            assert mod.main()==0 and len(incidents)==before+1
            print("PASS: non-2xx HTTPS notification retried without state loss")
            counts["deadLetters"]=1
            server.next_status=302
            assert mod.main()!=0
            before=len(incidents)
            assert mod.main()==0 and len(incidents)==before+1
            print("PASS: HTTP redirect blocked, next iteration retries")
            assert not failures
            # Direct security boundary test: a private DNS address is rejected
            # before any socket connection is attempted.
            with mock.patch.object(mod.socket,"getaddrinfo",
                return_value=[(socket.AF_INET,socket.SOCK_STREAM,6,"",("127.0.0.1",443))]):
                try:mod.safe_url()
                except ValueError:pass
                else:raise AssertionError("Private DNS accepted")
            print("PASS: private address rejected at DNS validation")
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=4)
print("REAL_ISOLATED_TLS_WEBHOOK_DELIVERY_PASS")
