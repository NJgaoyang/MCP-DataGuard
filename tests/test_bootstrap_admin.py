import importlib.util
import os
import pathlib
import unittest
from unittest.mock import patch
from types import SimpleNamespace

PATH = pathlib.Path(__file__).resolve().parents[1] / "bin" / "bootstrap-admin.py"
SPEC = importlib.util.spec_from_file_location("dataguard_bootstrap", PATH)
BOOTSTRAP = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(BOOTSTRAP)


class BootstrapAdminTests(unittest.TestCase):
    def test_canonical_identity_hash(self):
        self.assertEqual(64, len(BOOTSTRAP.oidc_digest("issuer", "subject")))
        self.assertNotEqual(BOOTSTRAP.oidc_digest("ab", "c"),
                            BOOTSTRAP.oidc_digest("a", "bc"))
        self.assertEqual(BOOTSTRAP.oidc_digest("issuer", "subject"),
                         BOOTSTRAP.oidc_digest("issuer", "subject"))

    def args(self, **overrides):
        values = dict(org_code="demo", org_name="Demo", issuer="https://idp.example",
                      subject="client/service", username="admin", display_name="Admin")
        values.update(overrides)
        return SimpleNamespace(**values)

    def test_rejects_admin_superuser_and_invalid_inputs(self):
        with patch.dict(os.environ, {"PGHOST": "127.0.0.1", "PGUSER": "dg_platform"}):
            BOOTSTRAP.checked(self.args())
            with self.assertRaises(ValueError):
                BOOTSTRAP.checked(self.args(org_code="a';DROP TABLE"))
            with self.assertRaises(ValueError):
                BOOTSTRAP.checked(self.args(subject=""))
        with patch.dict(os.environ, {"PGHOST": "127.0.0.1", "PGUSER": "postgres"}):
            with self.assertRaises(ValueError):
                BOOTSTRAP.checked(self.args())


if __name__ == "__main__":
    unittest.main()
