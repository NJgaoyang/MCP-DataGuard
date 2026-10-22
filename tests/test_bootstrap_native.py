import importlib.util
import pathlib
import unittest
PATH=pathlib.Path(__file__).resolve().parents[1]/"bin"/"bootstrap-native-admin.py"
SPEC=importlib.util.spec_from_file_location("native_bootstrap",PATH)
BOOT=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BOOT)
class LocalBootstrapTest(unittest.TestCase):
 def test_canonical_prefix_and_uniqueness(self):
  self.assertEqual(64,len(BOOT.digest(BOOT.ISSUER,"local-user:abc")))
  self.assertNotEqual(BOOT.digest("ab","c"),BOOT.digest("a","bc"))
 def test_password_hash_support(self):
  import crypt
  self.assertIn(crypt.METHOD_BLOWFISH,crypt.methods)
  x=crypt.crypt("arbitrary-long-test-password",crypt.mksalt(crypt.METHOD_BLOWFISH,rounds=2**12))
  self.assertTrue(x.startswith(("$2b$12$","$2y$12$")))
  self.assertEqual(x,crypt.crypt("arbitrary-long-test-password",x))
