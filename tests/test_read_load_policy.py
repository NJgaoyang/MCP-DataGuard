import importlib.util
import pathlib
import unittest

spec=importlib.util.spec_from_file_location("read_load",
 pathlib.Path(__file__).resolve().parents[1]/"bin/run-certified-mcp-read-load.py")
mod=importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

class ReadLoadSafetyTest(unittest.TestCase):
 def test_requires_https_and_exact_host(self):
  self.assertEqual(mod.validate_target("https://mcp.example.com/mcp","mcp.example.com"),
    "https://mcp.example.com/mcp")
  for url in ["http://mcp.example.com/mcp","https://evil.example.com/mcp",
              "https://mcp.example.com:8080/mcp","https://mcp.example.com/redirect"]:
   with self.assertRaises(ValueError):mod.validate_target(url,"mcp.example.com")
 def test_rejects_write_and_batch(self):
  self.assertEqual(mod.safe_select("SELECT id FROM foo LIMIT 1"),"SELECT id FROM foo LIMIT 1")
  for sql in ["UPDATE x SET a=1","SELECT 1; DROP TABLE x","WITH x AS (DELETE FROM t) SELECT 1"]:
   with self.assertRaises(ValueError):mod.safe_select(sql)
 def test_p95(self):
  self.assertEqual(mod.percentile95(list(range(1,101))),95)
  self.assertEqual(mod.percentile95([]),0)
if __name__=="__main__":unittest.main()
