import importlib.util
import pathlib
import unittest
path=pathlib.Path(__file__).resolve().parents[1]/"bin/verify-production-runtime.py"
spec=importlib.util.spec_from_file_location("runtime_policy",path)
policy=importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)
D="a"*64
MANIFEST={"images":{k:"repo.invalid/"+k+"@sha256:"+D for k in
    ("gateway","workflow","web","caddy")}}
def fixture(role,stage="closed"):
    key="caddy" if role=="public" else role
    ref=MANIFEST["images"][key]
    e={"DG_ENABLE_READ_EXECUTION":str(stage!="closed").lower(),
       "DG_ENABLE_WRITE_EXECUTION":str(stage=="write").lower(),
       "DG_WRITE_QUEUE_ENABLED":str(stage=="write").lower(),
       "DG_WRITE_RECOVERY_ENABLED":str(stage=="write").lower(),
       "FLOWABLE_OUTBOX_ENABLED":str(stage in ("approval","write")).lower(),
       "FLOWABLE_DYNAMIC_DEPLOYMENT_ENABLED":"false",
       "FLOWABLE_SCHEMA_UPDATE":"false",
       "PLATFORM_JDBC_URL":"jdbc:postgresql://db/prod?sslmode=verify-full",
       "FLOWABLE_JDBC_URL":"jdbc:postgresql://db/prod?sslmode=verify-full"}
    c={"State":{"Running":True},"Image":"sha256:"+"b"*64,
       "Config":{"Image":ref,"User":"10001:10001",
                 "Labels":{"com.docker.compose.project":policy.PROJECT,
                           "com.docker.compose.service":role},
                 "Env":[a+"="+b for a,b in e.items()]},
       "HostConfig":{"ReadonlyRootfs":True,"SecurityOpt":["no-new-privileges:true"],
          "CapDrop":["ALL"],"Memory":1073741824,"NanoCpus":1000000000,
          "PidsLimit":256,"Privileged":False,"NetworkMode":"dataguard-production_application",
          "PortBindings":({"80/tcp":[{"HostPort":"80"}],"443/tcp":[{"HostPort":"443"}]}
                          if role=="public" else {})}}
    return c,{"Id":c["Image"],"RepoDigests":[ref]}
def stack(stage="closed",workflow=True):
    c={};i={}
    for role in ("gateway","web","public")+(("workflow",) if workflow else ()):
        c[role],i[role]=fixture(role,stage)
    return c,i
class TestRuntimePolicy(unittest.TestCase):
    def test_closed_without_workflow(self):
        c,i=stack(workflow=False)
        self.assertTrue(policy.verify_all(MANIFEST,"closed",c,i))
    def test_closed_with_workflow_checked(self):
        c,i=stack()
        self.assertTrue(policy.verify_all(MANIFEST,"closed",c,i))
    def test_stale_outbox_closed_or_readonly_denied(self):
        for stage in ("closed","readonly"):
            c,i=stack(stage)
            c["workflow"]["Config"]["Env"].append("FLOWABLE_OUTBOX_ENABLED=true")
            with self.assertRaises(policy.NoGo):policy.verify_all(MANIFEST,stage,c,i)
    def test_workflow_required_for_approval_write(self):
        for stage in ("approval","write"):
            c,i=stack(stage,workflow=False)
            with self.assertRaises(policy.NoGo):policy.verify_all(MANIFEST,stage,c,i)
    def test_write_all_flags(self):
        c,i=stack("write")
        self.assertTrue(policy.verify_all(MANIFEST,"write",c,i))
        c["gateway"]["Config"]["Env"].append("DG_WRITE_RECOVERY_ENABLED=false")
        with self.assertRaises(policy.NoGo):policy.verify_all(MANIFEST,"write",c,i)
    def test_real_image_id_required(self):
        c,i=stack()
        i["gateway"]["Id"]="sha256:"+"c"*64
        with self.assertRaises(policy.NoGo):policy.verify_all(MANIFEST,"closed",c,i)
    def test_repo_digest_required(self):
        c,i=stack()
        i["web"]["RepoDigests"]=[]
        with self.assertRaises(policy.NoGo):policy.verify_all(MANIFEST,"closed",c,i)
    def test_never_publish_gateway(self):
        c,i=stack()
        c["gateway"]["HostConfig"]["PortBindings"]={"8080/tcp":[{"HostPort":"8080"}]}
        with self.assertRaises(policy.NoGo):policy.verify_all(MANIFEST,"closed",c,i)
    def test_never_put_password_in_docker_env(self):
        c,i=stack()
        c["gateway"]["Config"]["Env"].append("PG_PLATFORM_PASSWORD=public")
        with self.assertRaises(policy.NoGo):policy.verify_all(MANIFEST,"closed",c,i)
    def test_unrecognized_container(self):
        c,i=stack()
        c["postgres"],i["postgres"]=fixture("gateway")
        with self.assertRaises(policy.NoGo):policy.verify_all(MANIFEST,"closed",c,i)
if __name__=="__main__":unittest.main()
