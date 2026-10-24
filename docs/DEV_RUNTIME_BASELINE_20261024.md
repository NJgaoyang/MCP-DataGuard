# Read-only test-server runtime inventory — 2026-10-24

Scope: `VM-0-4-tencentos` **development/test host**. This page is a diagnostic snapshot, not a certified production deployment or signoff.

Observed using privileged Docker/systemd **metadata only** (without inspecting passwords, database rows, SQL payloads, env values, or tokens):

| Area | Test-host evidence | Production implication |
| --- | --- | --- |
| Services | gateway, workflow, PostgreSQL, web and Caddy containers running | Live staging, **not a pre-production user certification** |
| Gateway / workflow / web / public | `verify-live-hardened-dev.sh` passed root filesystem, resource and privilege checks | Do not extrapolate to production |
| PostgreSQL | CPU/memory/PID limits active; `docker inspect` returned null for `HostConfig.SecurityOpt` | **no-new-privileges still needs controlled restart and DBA approval** |
| Local backup | encrypted-backup, health and retention systemd timers active | Production GPG custody, RPO/RTO and periodic restore still missing |
| Operational alert | timer inactive and `/etc/dataguard/operations-alert.env` absent | **Actual incident destination and on-call delivery not configured** |
| Signed release | no `/etc/dataguard/production-release.json` and no `/etc/dataguard/release-evidence` directory | **No certified release approval evidence** |

Repeat this safe inventory with `python3 bin/collect-runtime-gate-evidence.py`. It prints only selected Docker host controls and timer status; **it is not a substitute** for independent signed release, business tests or database recovery.

Production Go/No-Go is **NO-GO**. Use `docs/PRODUCTION_ACCEPTANCE_EXECUTION.md` for all missing evidence.
