# Flexible Alerting & Notification Service

Gets the right alert to the right people on the channel they prefer, and shows them the runbook to resolve it.
Built on Kafka Streams with exactly-once processing; rules, schemas and sources are data, changed at runtime
without restarting anything.

## Start

```bash
docker compose up --build
```

| What | Where |
|---|---|
| UI | http://localhost:3000 (pick a user on the login screen; no password, it is a demo login) |
| API | http://localhost:8080 |
| Mock mailbox (emails sent by the service) | http://localhost:8025 |

Needs Docker with about 6 GB of memory. First build downloads dependencies; after that a clean start takes
about 20 seconds.

Send a first event:

```bash
curl -X POST http://localhost:8080/ingest/metrics-webhook -H 'X-Source-Token: demo-metrics-webhook' -d '{
  "alertname":"HighErrorRate","labels":{"service":"payments-api","region":"eu-west","severity":"critical"},
  "annotations":{"summary":"Error rate 12% over the last 5 minutes"},"startsAt":"2026-10-23T09:13:00Z","status":"firing"}'
```

Log in as **anna** to see it. Log in as **ravi** (another team) to see that he cannot.

## Seeded demo data

| User | Team and role | Good for showing |
|---|---|---|
| `admin` | platform admin | channel failure injection, everything visible |
| `sam` | Settlements admin, on-call | approving risky runbook steps |
| `sara` | Settlements member | running safe steps, being refused approval and rule edits |
| `petra` | Pricing admin | free-text runbook found by search |
| `ravi` | Rates admin | emails in the mock mailbox, granting a source to another team |
| `anna` | Payments admin | personal rule with quiet hours 20:00–08:00 (falls back to email) |
| `omar` | Payments member | acknowledging |

Sources (token is `demo-<source id>`): `settlement-bus`, `metrics-webhook`, `trade-gateway`, `pricing-logs`.
The four example rules and three runbooks from the brief's appendices are loaded.

## Tests

```bash
cd platform && mvn test          # 19 unit and topology tests (no Docker needed)
python3 scripts/smoke.py         # 57 end-to-end checks against the running stack
```

The smoke test covers: ingest and token check, de-duplication into one alert with a counter, one notification for
61 events, team isolation, acknowledge / auto-resolve / manual resolve, history filters (nested values, ranges,
`IN`, `OR`, injection attempt), email in the mock mailbox, channel failure with retry and no duplicate, rules
created at runtime, source grants and revocation, an event with a schema never seen before, and runbook
matching, placeholder filling, actions, approval and audit.

## Benchmark

```bash
BENCH_EVENTS=150000 BENCH_RATE=5000 docker compose --profile bench run --rm bench
```

Measured on one laptop (11 CPUs, 8 GB given to Docker) running everything: 3 brokers with replication factor 3,
2 processor instances, exactly-once enabled, commit interval 100 ms. Events are a mix of the three sample schemas;
every event matches a rule. Raw results are in `bench/results/`.

| Run | Events | Lost | Throughput | Ingest → alert p50 / p95 / p99 | Ingest → notification p50 / p95 / p99 |
|---|---|---|---|---|---|
| 5,000 events/s (below saturation) | 150,000 | 0 | 4,950/s | 243 / 387 / 474 ms | 419 / 470 / 481 ms |
| 20,000 events/s offered (saturated) | 200,000 | 0 | 12,083/s | queueing: 5,987 / 10,600 / 12,534 ms | 1,666 / 2,908 / 3,963 ms |

Latency below saturation is dominated by the two exactly-once commits an event passes through (2 × 100 ms).
`COMMIT_INTERVAL_MS` trades latency against throughput. "Notification" latency is to the record being readable on
the `notifications` topic; the dispatcher's own delivery latency is stored per delivery and shown in the UI.

## How it works

```
sources ─► ingest.<source> topics ─► [normalise + match rules] ─► alerting.matches ─► [alert state + fan-out]
 webhook gateway / Kafka producers        stateless                 keyed by alert        one writer per alert
                                                                                              │           │
                              cfg (compacted) ── rules, schemas, teams, users ──► both        ▼           ▼
                                                                                           alerts    notifications
                                                                       API projector + live feed    dispatcher ─► email / SMS / phone
```

- **One fixed topology.** Rules are records on the compacted `cfg` topic, compiled into in-memory predicates and
  indexed by source. Editing a rule restarts nothing.
- **Any schema.** Unknown JSON is ingested with heuristic severity, time and type; rules address raw paths
  (`payload.notional > 10000000`). A registered schema adds mapping; it never causes an event to be dropped.
  Only non-JSON goes to `ingest.dlq`.
- **Filter language.** A WHERE-clause subset (`AND`/`OR`/`NOT`, `IN`, `BETWEEN`, `LIKE`, `IS NULL`) parsed to one
  syntax tree used by the stream evaluator, the builder UI and the history query (compiled to parameterised SQL).
- **Access control.** Default deny. A team owns its sources, rules and runbooks; reading another team's source
  needs a grant from its admin. The permitted sources are stamped into each published rule, so the per-event check
  is an in-memory lookup.
- **Delivery.** Exactly-once inside Kafka. Dispatchers use an idempotency key, retry with backoff through a retry
  topic, and end in a dead-letter topic after five attempts.
- **Scaling.** Every role is the same image with a different `ROLE` and scales independently: processors up to the
  partition count (6), API and dispatcher freely.

Full detail: [`docs/SPEC.md`](docs/SPEC.md). Phases and test gates: [`docs/PLAN.md`](docs/PLAN.md).

## Adding things

| To add | Do this | Code? |
|---|---|---|
| Source | UI → Sources → Add (gets a topic, URL and token) | No |
| Schema | UI → paste a sample, pick severity/time/type fields | No |
| Rule | UI → Rules, or `POST /api/rules` | No |
| Runbook | UI → paste YAML, Markdown or any text | No |
| Action type that is an HTTP call | `POST /api/action-types` with URL and body templates | No |
| Delivery channel provider | one class implementing `ChannelAdapter`, listed in `ChannelAdapters.all()` | One class |

## What is mocked or not built

- **Login** is a user picker that issues a real signed JWT; there is no password or identity provider. Roles and
  access checks are enforced for real on the server.
- **Email** goes to Mailpit (a local mock SMTP server). **SMS and phone** are a log line plus a delivery record.
- **Action targets** (retry workflow, restart service, escalate, open ticket) call a mock endpoint inside the API.
  The sample runbook's `http://counterparty-service/health` is mapped to that mock.
- **Runbook search** uses Postgres full-text ranking, not embeddings. `RunbookSearch` is the swap point.
- **LLM** extraction of steps from prose is an interface (`LlmProvider`) with a no-op default; prose runbooks are
  shown as text steps.
- **Not built:** Kafka SASL/ACLs, archive to object storage, Kubernetes manifests, approval by SMS reply, change
  proposals with diffs for rules and runbooks, per-recipient rate limiting, tailed-log-file source, de-duplication
  of retried webhook posts, a transactional outbox (config is published after commit and republished on start),
  Prometheus/Grafana dashboards (each role exposes `/metrics` in Prometheus format, `/healthz` and `/readyz`).

## AI tools

Built with Claude Code from a written intention document. The review of that document, the specification and the
code were AI-assisted; no production data, credentials or proprietary code were given to the tool, and all sample
data comes from the hackathon brief.
