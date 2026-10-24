import importlib.util
import pathlib
import unittest
p=pathlib.Path(__file__).resolve().parents[1]/"bin/plan-production-stage-rollback.py"
s=importlib.util.spec_from_file_location("rollback_plan",p)
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class TestRollbackPlanner(unittest.TestCase):
 def test_adjacent_stages(self):
    for cur,nxt in (("write","approval"),("approval","readonly"),("readonly","closed")):
        plan=m.plan(cur,nxt,"CHANGE12345")
        self.assertEqual(plan["from"],cur)
        self.assertEqual(plan["to"],nxt)
        self.assertFalse(plan["commandsExecuted"])
        self.assertFalse(plan["automaticUnknownRetryAllowed"])
        self.assertEqual(plan["featureFlags"]["DG_ENABLE_WRITE_EXECUTION"],"false")
        self.assertEqual(plan["featureFlags"]["DG_WRITE_QUEUE_ENABLED"],"false")
        self.assertEqual(plan["featureFlags"]["DG_WRITE_RECOVERY_ENABLED"],"false")
 def test_emergency_direct_closed(self):
    self.assertEqual(m.plan("write","closed","INCIDENT123")["featureFlags"]["DG_ENABLE_READ_EXECUTION"],"false")
 def test_no_unsafe_stage_skips(self):
    with self.assertRaises(ValueError):m.plan("write","readonly","INCIDENT123")
 def test_no_forward_or_noop(self):
    for a,b in (("closed","readonly"),("readonly","write"),("approval","approval")):
        with self.assertRaises(ValueError):m.plan(a,b,"INCIDENT123")
 def test_change_id_validation(self):
    with self.assertRaises(ValueError):m.plan("write","approval","abc")
if __name__=="__main__":unittest.main()
