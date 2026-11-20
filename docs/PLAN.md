# Implementation plan

Each phase ends with a test gate. A phase is done only when its gate passes. Spec: `docs/SPEC.md`.

| Phase | Deliverable | Test gate |
|---|---|---|
| 0. Scaffold | Maven module, Dockerfile, `docker-compose.yml` (3 brokers, Postgres, Mailpit), role dispatch in `Main`, health endpoints. | `docker compose up -d` brings brokers healthy; `/healthz` answers on each role. |
| 1. Core library | Path resolver, filter AST + SQL parser/printer + evaluator, templates, normaliser, severity mapping. | Unit tests: the three Appendix A events normalise correctly; each example rule's filter parses, prints, round-trips and evaluates; GUI-compatibility detection; missing-placeholder marking. |
| 2. Topology | ConfigRegistry, NormaliseAndMatch, AlertState, fan-out. | TopologyTestDriver: 500 identical events → 1 alert with count 500 and one notification set; windowed rule fires only at threshold; resolve condition closes; ACK/RESOLVE commands; rule change applies without restart; ungranted source never matches; non-JSON → DLQ. |
| 3. API | Migrations, seed, auth, RBAC, config CRUD + publisher, ingest, projector, alert queries with filter, SSE, commands. | `scripts/smoke.sh`: login, post the webhook sample, alert appears via REST within 5 s, ack → status changes, member of another team gets 403/empty. |
| 4. Dispatcher | Adapters, idempotency, retry/DLQ, failure injection. | Smoke: email visible in Mailpit API; SMS row in deliveries; set SMS down → RETRYING, set up → SENT, exactly one SENT row. |
| 5. UI | All routes in SPEC section 12. | Build succeeds; manual walk-through of feed, rule editor preview, source onboarding from a pasted sample. |
| 6. Runbooks | Import (YAML/Markdown/text), selection, rendering, actions with allowlist and approval, audit, search suggestions. | Unit tests for the three Appendix B imports and an unknown format; smoke: execute `http_get` step, approval-required step blocks for a member and passes for an admin. |
| 7. Benchmark | Bench role, results file, README numbers. | A run completes and prints throughput and p50/p99; zero lost events (alerts count sum equals events sent). |
| 8. Polish | README (one-command start, what is mocked, AI usage note), demo script. | Fresh `docker compose up --build` from a clean clone works. |

Cut order if time runs out (last first): 8 polish extras, runbook search suggestions, quiet hours UI, saved filters.
