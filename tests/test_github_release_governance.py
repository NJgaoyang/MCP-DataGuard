import importlib.util
import pathlib
import unittest
p=pathlib.Path(__file__).resolve().parents[1]/"bin/check-github-release-governance.py"
s=importlib.util.spec_from_file_location("github_gate",p)
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
def enabled(flag):return {"enabled":flag}
def fixture(branch):
    statuses=m.DEV_CONTEXTS if branch=="dev20261022" else m.MAIN_CONTEXTS
    return {"protected":True},{
       "required_pull_request_reviews":{"required_approving_review_count":1,
           "dismiss_stale_reviews":True,"bypass_pull_request_allowances":{}},
       "required_conversation_resolution":enabled(True),
       "enforce_admins":enabled(True),
       "allow_force_pushes":enabled(False),"allow_deletions":enabled(False),
       "required_status_checks":{"checks":[{"context":x} for x in statuses],
                                 "strict":True}}
class GovernanceTest(unittest.TestCase):
 def test_secure_policy(self):
    for branch in ("main","dev20261022"):
        i,p=fixture(branch);self.assertTrue(m.check(branch,i,p))
 def test_unprotected_denied(self):
    i,p=fixture("dev20261022");i["protected"]=False
    with self.assertRaises(m.NoGo):m.check("dev20261022",i,p)
 def test_missing_supply_chain_check_denied(self):
    i,p=fixture("dev20261022")
    p["required_status_checks"]["checks"]=[
        x for x in p["required_status_checks"]["checks"]
        if x["context"]!="supply-chain-workflow"]
    with self.assertRaises(m.NoGo):m.check("dev20261022",i,p)
 def test_missing_review_denied(self):
    i,p=fixture("main");p["required_pull_request_reviews"]["required_approving_review_count"]=0
    with self.assertRaises(m.NoGo):m.check("main",i,p)
 def test_bypass_denied(self):
    i,p=fixture("main")
    p["required_pull_request_reviews"]["bypass_pull_request_allowances"]={"users":["owner"]}
    with self.assertRaises(m.NoGo):m.check("main",i,p)
 def test_admin_bypass_and_force_push_denied(self):
    i,p=fixture("main");p["enforce_admins"]["enabled"]=False
    with self.assertRaises(m.NoGo):m.check("main",i,p)
    i,p=fixture("main");p["allow_force_pushes"]["enabled"]=True
    with self.assertRaises(m.NoGo):m.check("main",i,p)
 def test_non_up_to_date_changes_denied(self):
    i,p=fixture("dev20261022");p["required_status_checks"]["strict"]=False
    with self.assertRaises(m.NoGo):m.check("dev20261022",i,p)
if __name__=="__main__":unittest.main()
