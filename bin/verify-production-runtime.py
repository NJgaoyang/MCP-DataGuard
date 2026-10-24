#!/usr/bin/env python3
"""Read-only, fail-closed inspection of the actual production Compose runtime.

The caller must first validate the independent GPG release signatures.
"""
import json
import os
import pathlib
import subprocess
import sys
ROLES=("gateway","workflow","web","public")
PROJECT="dataguard-production"
class NoGo(ValueError):pass
def require(value,why):
    if not value:raise NoGo(why)
def check_one(role,container,img,approved,stage):
    config=container.get("Config") or {}
    host=container.get("HostConfig") or {}
    labels=config.get("Labels") or {}
    require(container.get("State",{}).get("Running") is True,role+" not running")
    require(labels.get("com.docker.compose.project")==PROJECT,role+" project mismatch")
    require(labels.get("com.docker.compose.service")==role,role+" service mismatch")
    require(config.get("Image")==approved,role+" approved image mismatch")
    require(container.get("Image")==img.get("Id"),role+" image-content ID mismatch")
    require(approved in (img.get("RepoDigests") or []),role+" no trusted RepoDigest")
    require(host.get("ReadonlyRootfs") is True,role+" writable root")
    require("no-new-privileges:true" in (host.get("SecurityOpt") or []),role+" privileges")
    require("ALL" in (host.get("CapDrop") or []),role+" capabilities")
    require(host.get("Privileged") is not True and not host.get("PidMode")
        and host.get("NetworkMode")!="host",role+" host/privileged namespace")
    require(int(host.get("Memory") or 0)>0 and int(host.get("NanoCpus") or 0)>0
        and int(host.get("PidsLimit") or 0)>0,role+" unbounded resources")
    if role in ("gateway","workflow"):
        require(config.get("User") in ("10001","10001:10001"),role+" root JVM")
    env=dict(kv.split("=",1) for kv in config.get("Env",[]) if "=" in kv)
    for key in ("PG_PLATFORM_PASSWORD","PG_FLOWABLE_PASSWORD","POSTGRES_PASSWORD"):
        require(key not in env,role+" password in Docker inspect")
    if role!="public":
        require(not host.get("PortBindings"),role+" private TCP published")
    else:
        pub={k for k,v in (host.get("PortBindings") or {}).items() if v}
        require(pub=={"80/tcp","443/tcp"},"public Caddy must own only 80/443")
    if role=="gateway":
        flags={
          "DG_ENABLE_READ_EXECUTION":stage!="closed",
          "DG_ENABLE_WRITE_EXECUTION":stage=="write",
          "DG_WRITE_QUEUE_ENABLED":stage=="write",
          "DG_WRITE_RECOVERY_ENABLED":stage=="write"}
        for key,want in flags.items():
            require(env.get(key,"false").lower()==str(want).lower(),
                "gateway unexpected "+key+" for "+stage)
        require("sslmode=verify-full" in env.get("PLATFORM_JDBC_URL",""),
                "gateway not using verify-full database TLS")
    if role=="workflow":
        require(env.get("FLOWABLE_OUTBOX_ENABLED","false").lower() ==
            str(stage in ("approval","write")).lower(),"stale Flowable Outbox in "+stage)
        require(env.get("FLOWABLE_DYNAMIC_DEPLOYMENT_ENABLED")=="false" and
            env.get("FLOWABLE_SCHEMA_UPDATE")=="false","unsafe Flowable DDL/dynamic deploy")
        require("sslmode=verify-full" in env.get("FLOWABLE_JDBC_URL",""),
                "workflow not using verify-full database TLS")
def verify_all(manifest,stage,containers,images):
    require(stage in ("closed","readonly","approval","write"),"invalid stage")
    target={"gateway":manifest["images"]["gateway"],
            "workflow":manifest["images"]["workflow"],
            "web":manifest["images"]["web"],
            "public":manifest["images"]["caddy"]}
    roles=set(containers)
    required={"gateway","web","public"}
    if stage in ("approval","write"):required.add("workflow")
    require(required.issubset(roles),"required service missing")
    require(roles.issubset(set(ROLES)),"unrecognized service/container")
    # CRITICAL: always inspect stale Workflow, even if stage is closed/readonly.
    for role,entry in containers.items():
        require(role in images,"missing physical image")
        check_one(role,entry,images[role],target[role],stage)
    return True
def inspect(*args):
    p=subprocess.run(["docker",*args],text=True,capture_output=True,timeout=15)
    if p.returncode:raise NoGo("Docker metadata unavailable")
    try:return json.loads(p.stdout)
    except ValueError:raise NoGo("invalid Docker JSON")
def main():
    manifest=json.loads(pathlib.Path(os.environ["DG_RELEASE_MANIFEST"]).read_text())
    stage=os.environ["DG_DEPLOY_STAGE"]
    approved={"gateway":manifest["images"]["gateway"],
              "workflow":manifest["images"]["workflow"],
              "web":manifest["images"]["web"],
              "public":manifest["images"]["caddy"]}
    for role in ROLES:
        name="DG_"+("CADDY" if role=="public" else role.upper())+"_IMAGE"
        require(os.environ.get(name)==approved[role],"image input mismatch")
    p=subprocess.run(["docker","ps","-aq","--filter",
        "label=com.docker.compose.project="+PROJECT],capture_output=True,text=True,timeout=15)
    require(p.returncode==0,"cannot discover production containers")
    containers={}
    for cid in p.stdout.splitlines():
        record=inspect("inspect",cid)
        require(isinstance(record,list) and len(record)==1,"container inspect ambiguous")
        c=record[0]
        role=(c.get("Config",{}).get("Labels") or {}).get("com.docker.compose.service")
        require(role in ROLES and role not in containers,"unexpected or duplicate service")
        containers[role]=c
    images={}
    for role in containers:
        record=inspect("image","inspect",approved[role])
        require(isinstance(record,list) and len(record)==1,"image inspect ambiguous")
        images[role]=record[0]
    verify_all(manifest,stage,containers,images)
    for role in ROLES:
        if role in containers:print("RUNTIME_VERIFIED: "+role)
    print("PRODUCTION_RUNTIME_SIGNED_IMAGE_AND_POLICY_PASS")
if __name__=="__main__":
    try:main()
    except (KeyError,NoGo,OSError,ValueError,TypeError,subprocess.SubprocessError) as err:
        # Avoid echoing untrusted stderr/credentials.
        print("PRODUCTION_RUNTIME_NO_GO: "+type(err).__name__,file=sys.stderr)
        sys.exit(1)
