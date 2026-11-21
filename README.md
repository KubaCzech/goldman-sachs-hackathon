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

Needs Docker with about 6 GB of memory. One command, no other setup. Measured on the laptop used for the benchmark:
building the two service images from scratch takes about 70 s once Docker has the base images (a fresh machine
first downloads Kafka, Postgres, Mailpit and the Java/Node base images, which depends on the network); after that
a clean start takes about 20 s.

Send a first event:

```bash
curl -X POST http://localhost:8080/ingest/metrics-webhook -H 'X-Source-Token: demo-metrics-webhook' -d '{
  "alertname":"HighErrorRate","labels":{"service":"payments-api","region":"eu-west","severity":"critical"},
  "annotations":{"summary":"Error rate 12% over the last 5 minutes"},"startsAt":"2026-10-23T09:13:00Z","status":"firing"}'
```

Log in as **anna** to see it. Log in as **ravi** (another team) to see that the Rates team cannot.

Watch something instead of waiting for it to call you: the seeded `pricing-status` source fetches a status page
every 10 s. Make it fail and recover (log in as **petra**):

```bash
curl -X POST http://localhost:8080/mock/status/pricing-engine -H 'Content-Type: application/json' -d '{"status":"DOWN"}'
curl -X POST http://localhost:8080/mock/status/pricing-engine -H 'Content-Type: application/json' -d '{"status":"UP"}'
```

Events do not have to be JSON: the same endpoint takes CSV, YAML, XML, `key=value` lines, syslog or plain text.

```bash
curl -X POST http://localhost:8080/ingest/pricing-logs -H 'X-Source-Token: demo-pricing-logs' \
  --data-binary 'level=warn service=pricing-engine-2 instrument=EURUSD latencyMs=1240 msg="cache miss"'
```

## At a glance

| Area | What is built and measured | Evidence |
|---|---|---|
| **Flexibility** | Three kinds of source: webhook (pushed to us), Kafka topic, and HTTP polling (the service fetches a URL on a schedule, sends only changes, and turns "the URL stopped answering" into a `POLL_FAILED` event). New source, schema, rule, runbook and action type are configuration, made in the UI or API, live within seconds, no restart. Any input format (JSON, JSON lines, CSV/TSV, YAML, XML, key=value, syslog, form fields, plain text) and any schema, including ones never registered. Rules: a SQL `WHERE` subset (`AND`/`OR`/`NOT`, `IN`, `BETWEEN`, `LIKE`, `IS NULL`, nested paths, numeric and severity comparisons), "N events within M seconds", automatic resolve condition, re-notify interval, severity override, title/body templates, grouping. The same condition language searches alert history. A new delivery channel provider is one class. | UI → Sources, Rules; [Adding things](#adding-things); `core/Formats.java` |
| **Scalability** | One fixed exactly-once topology, scaled by partitions: add processor instances or threads, no code change. Rules: compacted Kafka topic held by every instance. Dedup and window state: per alert key in a local store backed by a Kafka changelog, so an instance can die and another takes over. Delivery status: Postgres with an idempotency key. Measured: processors 1 → 6 cores gives 2.9×; a broker's second core doubles throughput. Known limits: one very hot alert key is handled by one thread (a rule-design choice); Postgres is a single instance (configuration and read model; not on the event path). | [Benchmark](#benchmark), [How it works](#how-it-works), `scripts/bench_scaling.py` |
| **UX** | One command to start. A new team gets its first alert about 7 s after creating a source and a rule (measured: 3 API calls, or a few clicks). Rule builder: pick sources, then only their fields (from their schema and their recent events, with the values seen), typed operators, "match all / any", live preview against a sample in any format; SQL mode with live checking for complex rules. Help behind "?" icons, unsaved-changes guard, dark mode. Alerts show a rendered title, chosen fields, a filled-in runbook with runnable steps, and every delivery attempt. | UI at http://localhost:3000; [Start](#start) |
| **Throughput / latency** | At 1,500 events/s: ingest → alert p50 222 ms / p99 ≈ 370 ms, ingest → email delivered p50 411–665 ms / p99 610–860 ms. Capacity on one laptop: 36,000–56,000 events/s (default), up to 121,000 events/s (one broker on 4 cores). No lost events in any run, checked against Postgres. | [Benchmark](#benchmark), `bench/results/` |
| **Implementation** | Working end to end; 41 unit/topology tests and 91 end-to-end checks. | [Tests](#tests), [What is mocked or not built](#what-is-mocked-or-not-built) |
| **What is new here** | Rules hot-swapped as data in an exactly-once stream; one condition language compiled three ways (stream predicate, visual builder, parameterised SQL over history); format- and schema-agnostic ingest; runbooks that fill themselves from the alert, run actions behind approvals, learn a link when someone confirms a suggestion, and are written back from a handled incident ("Save what was done as a runbook" turns the audit trail and notes into a reviewed draft, with that incident's values turned back into placeholders); a benchmark suite that found and fixed a real event-loss race. | [How it works](#how-it-works) |

## Seeded demo data

| User | Team and role | Good for showing |
|---|---|---|
| `admin` | platform admin | channel failure injection, everything visible |
| `sam` | Settlements admin, on-call | approving risky runbook steps |
| `sara` | Settlements member | running safe steps, being refused approval and team-rule edits, creating a private rule (Rules → My rules) |
| `petra` | Pricing admin | free-text runbook found by search |
| `ravi` | Rates admin | emails in the mock mailbox, granting a source to another team |
| `anna` | Payments admin | personal rule with quiet hours 20:00–08:00 (falls back to email) |
| `omar` | Payments member | acknowledging |

Sources (token is `demo-<source id>`): `settlement-bus`, `metrics-webhook`, `trade-gateway`, `pricing-logs`.
The four example rules and three runbooks from the brief's appendices are loaded.

## Tests

```bash
cd platform && mvn test          # 41 unit and topology tests (no Docker needed)
python3 scripts/smoke.py         # 91 end-to-end checks against the running stack
```

The smoke test covers: ingest and token check, de-duplication into one alert with a counter, one notification for
61 events, team isolation, acknowledge / auto-resolve / manual resolve, history filters (nested values, ranges,
`IN`, `OR`, injection attempt), email in the mock mailbox, channel failure with retry and no duplicate, rules
created at runtime, private rules (visible and notified to their owner only), source grants and revocation, an event with a schema never seen before, and runbook
matching, placeholder filling, actions, approval and audit.

## Benchmark

Two event mixes are used. **Spread** sends the metrics and trade samples only; their rules group by a
per-event key (region, trade id), so the load spreads evenly over all partitions and threads. **Original mix** adds the
settlement sample, whose seeded rule groups every failure of the workflow into one single alert: a third of all events
then go to one alert, which one thread must handle in order. Spread shows what the system can do; the original mix
shows what one very hot alert costs.

Everything below was measured on one laptop, a MacBook Pro with an Apple M3 Pro (11 cores: 5 performance, 6 efficiency; 18 GB),
with all 11 cores and 8 GB given to Docker, running the whole system: 3 brokers
(replication factor 3), Postgres, API, dispatcher, the load generator and the processors. Events are the three sample
schemas; every event matches a rule. Every run is checked twice: the benchmark's own tally and, independently, the sum
of alert counters in Postgres must both equal the events sent. **No run lost an event.** Raw results: `bench/results/`.

```bash
python3 scripts/bench_scaling.py --burst --configs 2x0x3          # default deployment: capacity + latency
python3 scripts/bench_scaling.py --burst --cpuset                 # processor scaling on dedicated cores
python3 scripts/bench_scaling.py --burst --broker-cores 1,2,3,4,6 # one broker on 1..6 cores (separate stack)
scripts/kafka_baseline.sh                                         # raw Kafka on this machine, for comparison
```

**Latency at 1,500 events/s** (default deployment, exactly-once, commit interval 100 ms):

| Ingest → alert p50 / p95 / p99 | Ingest → delivered email p50 / p99 |
|---|---|
| 222 / 318–321 / 366–377 ms | 411–665 / 610–860 ms (two runs) |

"Delivered" is the moment the email is accepted by the mail server (Mailpit), as stored per delivery. It includes 400
alerts created within about half a second at the start of the run, all emailed at once.

**Capacity** is measured by sending a backlog much faster than it can be processed (150,000–760,000 events/s) into
running processors and timing the output. The default deployment (2 processors × 3 threads, nothing pinned)
processed **36,000–56,000 events/s** across four runs; the spread comes from sharing cores with the brokers.

**Processor scaling** (processors on dedicated cores; brokers and everything else share the other 5 cores):

| Processor cores | 1 | 2 | 3 | 4 | 6 |
|---|---|---|---|---|---|
| Events/s, keys spread evenly | 19,865 | 35,859 | 45,872 | 53,581 | 58,286 |
| Speed-up | 1.0× | 1.8× | 2.3× | 2.7× | 2.9× |

Processing is CPU-bound and scales with cores until the 5 shared cores (3 brokers, load generator, Postgres) are full.
Docker's cores map onto a mix of performance and efficiency cores that macOS chooses, so per-core figures are approximate.
With separate broker machines the processor tier has more room.

**Broker scaling** (one broker, replication factor 1, on dedicated cores; processors fixed):

| Broker cores | 1 | 2 | 3 | 4 | 6 |
|---|---|---|---|---|---|
| Pipeline events/s | 59,020 | 108,050 | 118,227 | 120,992 | 115,407 |
| Raw Kafka produce events/s | 816,000 | 1,808,000 | – | 2,188,000 | 2,208,000 |
| Raw produce p99 | 600 ms | 33 ms | – | 7 ms | 7 ms |

A one-core broker is busy only 90% of the time but answers slowly (p99 600 ms), and exactly-once commits wait for it;
a second core halves that wait and doubles the pipeline. Raw Kafka on the 3-broker stack does about 1.59 million
events/s, so the pipeline's per-event work (parse, match, update alert state, three writes) sets its limit, not Kafka.

**One hot alert caps throughput.** The seeded settlement rule groups every failure of one workflow into one alert, so a
third of the original mix lands on one partition and one thread. In the single-broker test, keys spread evenly reach
about 120,000 events/s, while the original mix stays near 58,000 events/s whatever the broker's core count. The fix is in the
rule (group by something finer), not in hardware.

**Exactly-once costs latency, not throughput** (default deployment, same day):

| Setting | Events/s (spread / original mix) | Ingest → alert p50 / p99 |
|---|---|---|
| Exactly-once, commit 100 ms (default) | 55,939 / 51,738 | 215 / 397 ms |
| At-least-once | 51,507 / 42,833 | 18 / 73 ms |
| Exactly-once, commit 1,000 ms | 60,411 / 43,901 | 1,676 / 2,068 ms |

Exactly-once makes each event wait for a commit at both stages (about 200 ms). It is kept because it guarantees an
alert's counter and notification decision are not repeated after a crash.

## How it works

```
sources ─► ingest.<source> topics ─► [normalise + match rules] ─► alerting.matches ─► [alert state + fan-out]
 webhook, Kafka producers, poller (URLs) stateless                 keyed by alert        one writer per alert
                                                                                              │           │
                              cfg (compacted) ── rules, schemas, teams, users ──► both        ▼           ▼
                                                                                           alerts    notifications
                                                                       API projector + live feed    dispatcher ─► email / SMS / phone
```

- **One fixed topology.** Rules are records on the compacted `cfg` topic, compiled into in-memory predicates and
  indexed by source. Editing a rule restarts nothing.
- **Any format, any schema.** A source accepts JSON, JSON lines, CSV/TSV (header row), YAML, XML, key=value
  (logfmt), syslog, form fields and plain text (one event per line). The format is detected per message unless the
  source fixes it; a `Content-Type` is a hint. A dotted CSV header or key (`payload.notional`) becomes a nested field,
  and numbers become numbers, so rules are the same whatever the format. Unknown schemas are ingested with heuristic
  severity, time and type; a registered schema adds mapping and never causes an event to be dropped. Only bodies that
  are not text at all go to `ingest.dlq`.
- **Filter language.** A WHERE-clause subset (`AND`/`OR`/`NOT`, `IN`, `BETWEEN`, `LIKE`, `IS NULL`) parsed to one
  syntax tree used by the stream evaluator, the builder UI and the history query (compiled to parameterised SQL).
- **Access control.** Default deny. A team owns its sources, rules and runbooks; reading another team's source
  needs a grant from its admin. The permitted sources are stamped into each published rule, so the per-event check
  is an in-memory lookup.
- **Delivery.** Exactly-once inside Kafka. Dispatchers use an idempotency key, retry with backoff through a retry
  topic, and end in a dead-letter topic after five attempts.
- **Scaling.** Every role is the same image with a different `ROLE` and scales independently: processors up to the
  partition count (6), API and dispatcher freely, pollers by splitting the polling sources (`POLLER_SHARDS`,
  `POLLER_INDEX`).
- **Polling sources.** The `poller` role fetches each polling source's URL on its schedule and writes the response
  into the source's ingest topic, so from there it is parsed and matched like any pushed event. An unchanged response
  is skipped by default. The first failed fetch sends a `POLL_FAILED` event, the first success after that a
  `POLL_RECOVERED` one. The URL's host must be on the allowlist, as for runbook actions.

Full detail: [`docs/SPEC.md`](docs/SPEC.md). Phases and test gates: [`docs/PLAN.md`](docs/PLAN.md).

## Describe a filter in words (optional)

With an Anthropic API key, the rule editor and the alert search get a "Describe it in words" box: type
"critical alerts from payments or trades over 10 million" and it writes the filter into the builder or the SQL tab.

```bash
ANTHROPIC_API_KEY=sk-ant-... docker compose up -d        # or put the key in a .env file (ignored by git)
```

Without a key the box is hidden and no model is ever called. How it is kept safe:

- **The model writes our filter language, not database SQL.** Its answer goes through the same parser as hand-typed
  text; anything outside the grammar is rejected (it gets one chance to correct itself), and history queries stay
  parameterised. A wrong answer is a wrong filter the person can see, never an injection.
- **A person decides.** The filter is placed in the editor with a note to read it; nothing is saved or applied by itself.
- **No event data leaves.** The model is sent the description and the names and types of the available fields; values
  seen in events are not sent.
- **Not in the alert path.** Only the editors call it; the event stream never does.
- **Replaceable provider.** One small interface (`Assistant.Llm`); the default calls Claude (`LLM_MODEL`, default
  `claude-opus-5-5`; `LLM_EFFORT`, default `low`). A self-hosted model would implement the same interface.

Not verified against the live API in this repository's test run: the unit tests use a stand-in model, and the
end-to-end check runs the real call only when a key is present.

## Runbooks: steps, actions and approvals

A runbook says what to do when an alert fires. Write it in the UI (Runbooks → New runbook) or `POST /api/runbooks`,
in any form: YAML gives runnable steps, Markdown lists become steps, any other text is kept as it is. Example (the
brief's Appendix B, loaded by default):

```yaml
runbook: settlement-timeout
matches:                       # which events it is for, when no rule links it
  type: WORKFLOW_FAILED
  error.code: TIMEOUT
steps:
  - check: "Is counterparty-service healthy?"
    action: http_get           # built-in action
    url: "http://counterparty-service/health"
  - action: retry_workflow     # an action type defined as configuration
    workflowId: "{{workflowId}}"
    approval: none
  - if: "retry fails twice"
    action: escalate
    team: counterparty-support
    approval: required         # waits for a team admin
```

**Which runbook an alert shows.** In order: the runbook linked to the alert's rule; otherwise the team's runbook whose
`matches` all hold for the event, the most specific (most keys) winning; otherwise the team's runbooks are searched
for words in the alert and shown as suggestions with a match score. "Use this runbook" links the chosen one to the
rule, so the next alert of that rule shows it directly.

**Steps.** Placeholders such as `{{workflowId}}` or `{{labels.service}}` are filled from the alert's event; one that
cannot be filled is marked in the UI, and an action whose URL is missing a value is refused. The full steps appear on
the alert page and in emails; SMS and phone get a link.

**Actions.** A step with `action:` is run by the service when someone clicks Run on the alert. `http_get` and
`http_post` are built in. Any other action is configuration: a method, a URL template and a body template, added by a
platform admin on the Runbooks page or with

```bash
curl -X POST http://localhost:8080/api/action-types -H "Authorization: Bearer <admin token>" -H 'Content-Type: application/json' \
  -d '{"type":"page_oncall","method":"POST","urlTemplate":"http://pager/teams/{{team}}/page","bodyTemplate":"{\"alert\":\"{{type}}\"}"}'
```

A step's own settings fill the template first, then the alert's event. The target host must be on the allowlist
(table `host_allowlist`, global or per team), so a runbook cannot call arbitrary addresses.

**Approvals.** `approval: required` makes a step wait when a team member runs it; a team admin of the alert's team
approves (the action then runs) or rejects it. Every run, approval, rejection and result is in the alert's history.

**From incident to runbook.** On a resolved alert, "Save what was done as a runbook" writes a draft from the history:
the steps that were run (with their placeholders), and the notes left when acknowledging or resolving, with this
alert's values turned back into placeholders. It opens in the runbook editor to be checked and saved.

## Adding things

| To add | Do this | Code? |
|---|---|---|
| Source | UI → Sources → Add: a webhook, a Kafka topic, or a URL the service fetches on a schedule (gets a topic, URL and token; pick a message format or leave it on automatic) | No |
| Input format | parser in `core/Formats.java` (detection + one method) | One method |
| Schema | UI → paste a sample, pick severity/time/type fields | No |
| Rule | UI → Rules, or `POST /api/rules` | No |
| Runbook | UI → paste YAML, Markdown or any text | No |
| Action type that is an HTTP call | `POST /api/action-types` with URL and body templates | No |
| Delivery channel provider | one class implementing `ChannelAdapter`, listed in `ChannelAdapters.all()` | One class |

## Identity and teams (demo vs production)

The login screen is a **demo**: pick a user, get a signed token. Users, teams and roles are seeded. In production
the sign-in is the company's SSO (OIDC or SAML). The identity provider's token carries the user and their directory
groups, and groups map to teams and roles (for example `grp-settlements-admins` → admin of Settlements). Every access
check in the service reads one resolved object, the caller's teams and roles (`Auth.Caller`), built in one place
(`Auth.load`). Switching to SSO replaces that one function with "verify the token and map its groups"; nothing else
changes. Team-specific settings that a directory does not hold (who is on call, whether the whole team or only the
on-call person is notified) stay in the service, under Admin. Not built: the SSO adapter itself.

## What is mocked or not built

- **Login** is a user picker that issues a real signed JWT; there is no password or identity provider. Roles and
  access checks are enforced for real on the server.
- **Watched status page**: `/mock/status/<name>` in the API stands in for a real service's status page (GET to read,
  POST to change it).
- **Email** goes to Mailpit (a local mock SMTP server). **SMS and phone** are a log line plus a delivery record.
- **Action targets** (retry workflow, restart service, escalate, open ticket) call a mock endpoint inside the API.
  The sample runbook's `http://counterparty-service/health` is mapped to that mock.
- **Runbook search** uses Postgres full-text ranking, not embeddings. `RunbookSearch` is the swap point.
- **LLM**: the only model call is the optional "describe a filter in words" assistant above. Extraction of steps
  from prose runbooks is still an interface (`LlmProvider`) with a no-op default; prose runbooks are
  shown as text steps.
- **Not built:** Kafka SASL/ACLs, archive to object storage, Kubernetes manifests, approval by SMS reply, change
  proposals with diffs for rules and runbooks, per-recipient rate limiting, tailed-log-file source, de-duplication
  of retried webhook posts, a transactional outbox (config is published after commit and republished on start),
  Prometheus/Grafana dashboards (each role exposes `/metrics` in Prometheus format, `/healthz` and `/readyz`).

## AI tools

Built with Claude Code from a written intention document. The review of that document, the specification and the
code were AI-assisted; no production data, credentials or proprietary code were given to the tool, and all sample
data comes from the hackathon brief.
