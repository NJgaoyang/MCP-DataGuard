import importlib.util
import pathlib
import unittest
p=pathlib.Path(__file__).resolve().parents[1]/"bin/verify-production-public-https.py"
s=importlib.util.spec_from_file_location("https_probe",p)
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
H={"Strict-Transport-Security":"max-age=31536000; includeSubDomains",
   "Content-Security-Policy":"default-src 'self'; script-src 'self'",
   "X-Frame-Options":"DENY","X-Content-Type-Options":"nosniff",
   "Cache-Control":"no-store"}
class TestProductionIngress(unittest.TestCase):
 def test_real_dns_only(self):
  self.assertEqual(m.validate_host("gate.example.org"),"gate.example.org")
  for h in ("127.0.0.1","localhost","dev.invalid","foo.local","foo.internal","FOO.EXAMPLE.COM"):
   with self.assertRaises(ValueError):m.validate_host(h)
 def test_enforced_csp_and_private_routes(self):
  m.validate_response("/",200,H)
  m.validate_response("/api/v1/me",401,H)
  m.validate_response("/mcp",401,H)
  with self.assertRaises(ValueError):m.validate_response("/mcp",200,H)
 def test_report_only_not_accepted(self):
  headers={k:v for k,v in H.items() if k!="Content-Security-Policy"}
  headers["Content-Security-Policy-Report-Only"]="default-src 'self'"
  with self.assertRaises(ValueError):m.validate_response("/",200,headers)
 def test_no_cache_fail_closed(self):
  h={k:v for k,v in H.items() if k!="Cache-Control"}
  with self.assertRaises(ValueError):m.validate_response("/mcp",401,h)
if __name__=="__main__":unittest.main()
