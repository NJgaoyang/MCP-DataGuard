# MCP DataGuard

> 🚧 **Under active development — do not connect this branch to production databases.**

MCP DataGuard is an open-source, self-hosted SQL security gateway for AI Agents.
The target product includes Spring AI MCP tools, PostgreSQL-backed policy management,
database dialect plugins, Flowable approvals, audited read/write execution, and Vue 3 admin UI.

## Development branch

Branch: `dev20261022`. Main contains repository bootstrap only.

### Architectural baseline

- Java 21, Spring Boot 4, Spring AI 2, Flowable 8.
- **One PostgreSQL 17 server and database** `dataguard` with two schemas:
  `platform` and `flowable`. Use separate database roles.
- First target systems: MySQL and PostgreSQL via tightly scoped read-only credentials.
- Unrecognized SQL and uncategorized database functionality **fail closed**.
- No vendor license restrictions or paid feature unlocks.

### Status

This branch has design/contracts/migrations and initial service infrastructure.
The SQL safety subset is conservative, **disabled by default**, and is not a substitute
for a formally certified security gateway. The Flowable BPMN is a process template,
**not** a completed approval-to-execution integration.

### Development prerequisites

JDK 21, Maven 3.9+, PostgreSQL 17, Node 22 (for future Vue UI), Docker.

```bash
cp deploy/.env.example deploy/.env
# Edit secrets in deploy/.env
docker compose --env-file deploy/.env -f deploy/compose.yaml up -d
mvn -B -ntp verify
```

Read [PRD](docs/PRD.md), [technical design](docs/TECHNICAL_DESIGN.md), and
[development backlog](docs/DEVELOPMENT_BACKLOG.md) before enabling any SQL tools.
No database credentials should be committed. The shipped sample env values are placeholders.

### Current scope / gates

- [x] GitHub branch, design, MCP contract, OpenAPI, PostgreSQL 44-table baseline.
- [x] Spring service modules, JWT resource-server guards, Flowable BPMN example, SQL fail-closed validator.
- [ ] CI passing with dependency resolution and actual PostgreSQL startup.
- [ ] Audited tenant/agent/datasource authorization and real read-only tool execution.
- [ ] Flowable requester/approver authorization, persistence, reconciliation.
- [ ] A separate safe write executor with idempotency and crash recovery.
- [ ] Full Vue admin UI, dialect certification matrix and security tests.

**All write SQL must stay disabled until every security gate passes.**
