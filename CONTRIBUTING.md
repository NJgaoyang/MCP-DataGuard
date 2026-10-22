# Contributing
MCP DataGuard aims to be fully open source (Apache-2.0 pending finalized LICENSE metadata).
All changes touching SQL execution or authorization must include regression tests.
Never add a default allow rule, a production write credential, an approval bypass,
or a flow that retries uncertain database mutations.
Run `mvn verify` and contract checks before proposing a pull request.
