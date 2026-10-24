import importlib.util
import pathlib
import unittest

ROOT=pathlib.Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location("audit_anchor",ROOT/"bin/export-audit-anchor.py")
mod=importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

class CheckpointTest(unittest.TestCase):
    def test_deterministic_without_raw_sql(self):
        a="0123456789abcdef0123456789abcdef|mcp-read-sql|1|"+"f"*64
        b="fedcba9876543210fedcba9876543210|mcp-denied-sql|2|"+"a"*64
        digest,count=mod.digest_streams([a,b])
        self.assertEqual(count,2)
        self.assertEqual(digest,mod.digest_streams([b,a])[0])
        self.assertEqual(len(digest),64)
    def test_bad_chain_head_fails(self):
        with self.assertRaises(ValueError):
            mod.digest_streams(["a"*32+"|mcp-read-sql|1|not-a-hash"])
        with self.assertRaises(ValueError):
            mod.digest_streams(["wrong format"])
if __name__=="__main__":
    unittest.main()
