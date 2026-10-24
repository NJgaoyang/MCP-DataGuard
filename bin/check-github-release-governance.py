#!/usr/bin/env python3
"""Read-only GitHub release-governance check against the configured repository.

Never changes branch settings; production preflight requires an independent
GitHub-authorized read token in a 0600 file. Inaccessible rules fail CLOSED.
"""
import json
import os
from pathlib import Path
import stat
import ssl
import sys
import urllib.request

REPO="NJgaoyang/MCP-DataGuard"
BASE="https://api.github.com/repos/"+REPO
DEV_CONTEXTS={
    "backend","frontend","assets","postgres-ddl","deployment-spec",
    "isolated-security","release-hardening","production-packaging",
    "supply-chain-gateway","supply-chain-workflow","supply-chain-web"}
MAIN_CONTEXTS={"backend","frontend","assets","postgres-ddl","deployment-spec"}

class NoGo(ValueError):pass
def require(ok,reason):
    if not ok:raise NoGo(reason)
def check(branch,info,protection):
    require(info.get("protected") is True,branch+" not protected")
    pr=protection.get("required_pull_request_reviews") or {}
    require(int(pr.get("required_approving_review_count") or 0)>=1,
            branch+" independent PR review not required")
    require(pr.get("dismiss_stale_reviews") is True,branch+" stale review accepted")
    bypass=pr.get("bypass_pull_request_allowances") or {}
    require(not any(bypass.get(k) for k in ("users","teams","apps")),
            branch+" bypass approvers configured")
    require((protection.get("required_conversation_resolution") or {}).get("enabled") is True,
            branch+" unresolved PR conversations accepted")
    require((protection.get("enforce_admins") or {}).get("enabled") is True,
            branch+" admins may bypass checks")
    for field in ("allow_force_pushes","allow_deletions"):
        require((protection.get(field) or {}).get("enabled") is not True,
                branch+" dangerous "+field)
    checks=protection.get("required_status_checks") or {}
    required={x["context"] for x in checks.get("checks",[])
              if isinstance(x,dict) and isinstance(x.get("context"),str)}
    required.update(x for x in checks.get("contexts",[]) if isinstance(x,str))
    wanted=DEV_CONTEXTS if branch=="dev20261022" else MAIN_CONTEXTS
    require(wanted.issubset(required),
            branch+" missing required release security checks")
    require(checks.get("strict") is True,branch+" outdated PR commits permitted")
    return True

def secret():
    p=Path(os.environ["DG_GITHUB_RELEASE_TOKEN_FILE"])
    require(p.is_absolute() and p.is_file() and not p.is_symlink(),
            "token file must be absolute regular file")
    require(stat.S_IMODE(p.stat().st_mode)==0o600,"token file must be 0600")
    token=p.read_text().strip()
    require(32<=len(token)<=1024 and "\n" not in token,"invalid token")
    return token

def main():
    token=secret()
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self,*args,**kwargs):return None
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),
        NoRedirect(),urllib.request.HTTPSHandler(context=ssl.create_default_context()))
    def get(route):
        req=urllib.request.Request(BASE+route,headers={
            "Authorization":"Bearer "+token,
            "Accept":"application/vnd.github+json",
            "X-GitHub-Api-Version":"2022-11-28"})
        with opener.open(req,timeout=10) as resp:
            body=resp.read(1_000_001)
            require(len(body)<=1_000_000,"GitHub API response oversized")
            return json.loads(body)
    for branch in ("dev20261022","main"):
        info=get("/branches/"+branch)
        protection=get("/branches/"+branch+"/protection")
        check(branch,info,protection)
        print("GITHUB_BRANCH_GOVERNANCE_PASS: "+branch)
    print("GITHUB_RELEASE_GOVERNANCE_PASS")
if __name__=="__main__":
    try:main()
    except (OSError,ValueError,KeyError,TypeError,
            urllib.error.URLError) as exc:
        print("GITHUB_RELEASE_GOVERNANCE_NO_GO: "+type(exc).__name__,file=sys.stderr)
        sys.exit(1)
