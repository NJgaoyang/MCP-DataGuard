#!/usr/bin/env python3
"""Run a real HTTP MCP JSON-RPC contract smoke test without printing credentials."""
import json
import os
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("DATAGUARD_MCP_URL", "http://127.0.0.1:8080/mcp")
TOKEN = os.environ.get("DATAGUARD_AGENT_TOKEN")
if not TOKEN:
    sys.exit("Set DATAGUARD_AGENT_TOKEN to a valid active Agent token")


def call(method, params=None, token=TOKEN, request_id=1):
    body = json.dumps({"jsonrpc": "2.0", "id": request_id, "method": method,
                       "params": params or {}}).encode()
    headers = {"Content-Type": "application/json", "Accept": "application/json",
               "MCP-Protocol-Version": "2025-03-26"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(BASE, data=body, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=15) as result:
            return result.status, json.load(result)
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read().decode())


def main():
    status, result = call("initialize", {"protocolVersion": "2025-03-26",
         "capabilities": {}, "clientInfo": {"name": "dataguard-smoke", "version": "1.0"}})
    assert status == 200 and result.get("result", {}).get("protocolVersion"), (status, result)
    status, result = call("tools/list")
    assert status == 200, (status, result)
    tools = {tool["name"]: tool for tool in result["result"]["tools"]}
    expected = {"list_datasources", "get_schema", "describe_table", "execute_readonly"}
    assert expected.issubset(tools.keys()), sorted(tools)
    status, result = call("tools/call", {"name": "list_datasources", "arguments": {}})
    assert status == 200 and "result" in result, (status, result)
    status, _ = call("tools/list", token=None)
    assert status == 401, f"Unauthenticated MCP returned HTTP {status}, expected 401"
    status, result = call("tools/call", {"name": "describe_table", "arguments": {}})
    assert status == 200 and "error" in result, (status, result)
    print("PASS: initialize, tools/list, sources, authorization, invalid arguments")
    print("NOTE: SQL execution, real MySQL, field grants and third-party clients are NOT certified")


if __name__ == "__main__":
    main()
