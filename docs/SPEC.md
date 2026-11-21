# Flexible Alerting & Notification Service — Technical Specification

This document is the single source of truth for implementers. Where it conflicts with
`GS Hackaton.docx` (the intention document), this document wins: it records the decisions
taken during review. Scope is sized for an 8-hour build; section 14 lists what is cut.

## 1. Decisions (do not re-litigate)

| # | Decision | Reason |
|---|---|---|
| D1 | Rules are **data**, evaluated by one fixed Kafka Streams topology. No ksqlDB, no per-rule topology. | Editing a rule restarts nothing; cost per extra rule is one in-memory predicate; unknown schemas need no type declaration. |
| D2 | The "SQL view" is our own WHERE-clause subset parsed to a JSON AST (section 6). It never reaches Kafka or a database as text. | Same AST drives the stream evaluator, the GUI builder and the history query. |
| D3 | Postgres is the system of record for configuration. Every config write is committed to Postgres then published to the compacted topic `cfg`. On API start all config is republished (reconciliation). | Relational integrity for RBAC; Streams reads config as a table. A transactional outbox is the production upgrade. |
| D4 | Kafka exactly-once (`exactly_once_v2`) inside the pipeline. At the edge, dispatchers use an idempotency key (`notificationId`) stored in Postgres. | EOS cannot cover side effects outside Kafka. |
| D5 | No blocking I/O inside the topology. Dispatch, runbook actions and runbook search run in plain services. | Blocking stalls partitions and causes rebalances. |
| D6 | Lenient ingestion: an event is never dropped because of its schema or format. Unknown schemas get `schemaId = "unknown"` and heuristic canonical fields; any text format is parsed (section 15). Only non-text bodies go to `ingest.dlq`. | Unseen schemas and formats must still alert; dropping alerts is the worst failure. |
| D7 | Presentation uses logic-less `{{path}}` templates. | Sandboxed by construction; maps to a block editor. |
| D8 | RBAC is default-deny: owner team plus explicit grants. Enforced in the API and stamped into published rules (`allowedSources`) so the stream check is an in-memory lookup. | RBAC must not be a hot-path bottleneck. |
| D9 | Auth is a mock login (pick a seeded user, receive a JWT). RBAC is enforced for real server-side. | Demo scope. |
| D10 | Three notification layers: rule picks targets and optional channels; team policy picks who in the team; user preferences pick channels by severity and quiet hours. | Agreed with product owner. |
| D11 | LLM is an abstract provider interface with a no-op default. Runbook fallback search is Postgres full-text search behind a `RunbookSearch` interface; embeddings are a drop-in replacement. | Compliance: provider may be self-hosted later. |
| D12 | One Java codebase, one Docker image, role chosen by env `ROLE` (`api`, `processor`, `dispatcher`, `bench`). Each role scales independently. | Build speed. |
| D13 | Kafka SASL/ACLs, archive to object storage, Kubernetes, SMS-reply approvals, LLM extraction are documented but not built. | Time. |

## 2. Stack

- Java 21, Maven, single module `platform/`, shaded jar `platform.jar`, main class `gs.alerting.Main`.
- Kafka 3.9 (image `apache/kafka:3.9.0`), KRaft, 3 brokers, replication factor 3, `min.insync.replicas=2`.
- Kafka Streams 3.9.0, Javalin 6 (HTTP and SSE), Jackson, HikariCP + PostgreSQL JDBC, SnakeYAML, Angus Mail, SLF4J simple.
- Postgres 16. Mailpit as mock SMTP (web UI on host port 8025).
- UI: React 18 + Vite (JavaScript), served by nginx which proxies `/api` and `/ingest` to the API.
- Tests: JUnit 5, `kafka-streams-test-utils` (TopologyTestDriver). End-to-end: `scripts/smoke.sh` against the running compose stack.

Package root `gs.alerting`: `core` (paths, filter, templates, normaliser, model), `stream` (topology),
`api`, `dispatch`, `bench`, `util`.

## 3. Runtime components

| Service (compose) | Role | Scales by | Ports |
|---|---|---|---|
| `kafka1..3` | brokers | — | 9092 internal; kafka1 exposes 29092 to host |
| `postgres` | config record, alert read model, deliveries, audit | — | 5432 |
| `api` | REST, SSE, webhook ingest, config publisher, alert projector | replicas (stateless) | 8080 |
| `processor` | Kafka Streams topology | replicas up to partition count | 8081 (health) |
| `dispatcher` | notification delivery | replicas (consumer group) | 8082 (health) |
| `mailpit` | mock SMTP | — | 1025, 8025 |
| `ui` | nginx + static app | replicas | 3000 |
| `bench` | load generator (profile `bench`) | — | — |

Every Java role exposes `GET /healthz` (process alive), `GET /readyz` (dependencies ready; for the
processor: Streams state is `RUNNING`), `GET /metrics` (Prometheus text format).

## 4. Topics

All topics: 6 partitions, RF 3, unless noted. Created by the API role at startup with AdminClient (idempotent).

| Topic | Key | Value | Notes |
|---|---|---|---|
| `ingest.<sourceId>` | optional | raw bytes (JSON expected) | One per source, created when the source is created. Header `ingest-ts` (epoch ms, ASCII) set by the gateway; absent for direct Kafka producers (record timestamp is used). |
| `ingest.dlq` | sourceId | raw bytes | Unparseable input. |
| `cfg` | `<kind>:<id>` | JSON or tombstone | Compacted, **1 partition**. Kinds: `rule`, `schema`, `source`, `team`, `user`, `runbook`. |
| `alert.commands` | alertKey | AlertInput JSON (`kind=COMMAND`) | Must have the same partition count as the matches repartition topic. |
| `alerts` | alertId | Alert JSON | Compacted + 14 day retention (`cleanup.policy=compact,delete`). |
| `notifications` | userId | Notification JSON | |
| `notifications.retry` | userId | Notification JSON | Header-free; `attempt` and `notBefore` are in the value. |
| `notifications.dlq` | userId | Notification JSON | After 5 failed attempts. |

The processor subscribes with the pattern `ingest\.(?!dlq$).+` and `metadata.max.age.ms=5000` so a new source
topic is picked up within seconds.

## 5. Data model (JSON as stored in `cfg` and returned by the API)

IDs are lowercase slugs or UUIDs, always strings. Times are epoch milliseconds unless stated.

### 5.1 Source
```json
{"id":"settlement-bus","teamId":"settlements","name":"Settlement workflow bus",
 "type":"kafka|webhook","defaultSchemaId":"workflow-failed","token":"<random>",
 "grantedTeamIds":["rates"]}
```
`token` authenticates webhook posts. API responses add `topic` (`ingest.<id>`) and `ingestUrl` (`/ingest/<id>`).

### 5.2 Schema
```json
{"id":"trade-rejected","teamId":"rates","name":"Trade rejected v2","priority":10,
 "match": <AST or null>,
 "mapping":{
   "severity":{"path":"labels.severity","const":null,"map":{"ERROR":"error","WARN":"warning"}},
   "timestamp":{"path":"meta.ts","format":"epoch_ms|epoch_s|iso"},
   "type":{"path":"meta.schema","const":null},
   "service":{"path":"meta.producer","const":null}},
 "fields":[{"path":"payload.notional","type":"number|string|boolean","example":"25000000"}]}
```
Schema resolution for an event: among schemas with non-null `match` evaluate in descending `priority`, first
true wins; otherwise the source's `defaultSchemaId`; otherwise `unknown`.

Heuristics when a mapping entry is missing or `unknown` schema (first existing path wins):
- severity: `severity`, `level`, `labels.severity`, `priority`, `meta.severity`
- timestamp: `timestamp`, `ts`, `time`, `@timestamp`, `startsAt`, `meta.ts`, `meta.timestamp` (numbers ≥ 10^12 are ms, else seconds; strings parsed as ISO-8601)
- type: `type`, `alertname`, `meta.schema`, `event`, `eventType`, `name`
- service: `service`, `labels.service`, `meta.producer`, `workflowName`, `app`

Severity normalisation (case-insensitive) to `critical|error|warning|info|unknown`:
`critical,crit,fatal,emergency,page,p1→critical`; `error,err,high,p2→error`; `warning,warn,medium,p3→warning`;
`info,information,notice,low,debug,p4→info`; anything else or missing → `unknown`. A schema `map` is applied first.
Order for comparisons: `unknown(0) < info(1) < warning(2) < error(3) < critical(4)`.

### 5.3 Normalised event (in-memory envelope, also the rule evaluation context)
```json
{"eventId":"<uuid>","sourceId":"...","ownerTeamId":"...","schemaId":"...","receivedAt":0,"eventTime":0,
 "ingestTs":0,"severity":"error","type":"WORKFLOW_FAILED","service":"order-settlement","payload":{ ...original... }}
```
Field path resolution (used by filters and templates):
1. Canonical names `source`, `schema`, `severity`, `type`, `service`, `eventTime`, `receivedAt` resolve to the envelope.
2. `raw.<path>` resolves strictly inside the original payload.
3. Any other dotted path resolves inside the original payload (`error.code`, `payload.notional`, `labels.service`).
   Array index segments are allowed (`items.0.id`). A missing path is `null`.

### 5.4 Rule
```json
{"id":"...","teamId":"settlements","name":"Prod settlement failures","enabled":true,"version":3,
 "filter": <AST>, "filterSql":"type = 'WORKFLOW_FAILED' AND ...",
 "sourceIds":["settlement-bus"],            // null or [] = every source the team may read
 "allowedSources":["settlement-bus"],        // computed by the API; the processor trusts only this
 "groupBy":["workflowName","step"],          // fingerprint fields; [] = one alert per rule
 "window":{"count":5,"seconds":300},         // null = fire on first match
 "resolveWhen": <AST or null>,               // an event matching this (same source scope, same group) resolves the alert
 "severity":null,                            // null = take from event; else override
 "targets":[{"type":"team|user|oncall","id":"settlements"}],
 "channels":["phone","sms"],                 // null = recipient preferences decide
 "presentation":{"titleTemplate":"...","bodyTemplate":"...","fields":["step","error.message"],"color":"#d32f2f"},
 "runbookId":"settlement-timeout","renotifyMinutes":0}
```
`allowedSources` = (`sourceIds` if non-empty else all sources) ∩ (sources owned by `teamId` ∪ sources granted to
`teamId`). The API recomputes and republishes affected rules whenever a source or grant changes.

### 5.5 Team and user
```json
{"id":"settlements","name":"Settlements","notify":"all|oncall","onCallUserId":"u-sam",
 "members":[{"userId":"u-sam","role":"admin|member"}]}
{"id":"u-anna","username":"anna","displayName":"Anna","email":"anna@example.test","phone":"+440000001",
 "platformAdmin":false,
 "channels":{"critical":["sms"],"error":["email"],"warning":["email"],"info":[],"unknown":[]},
 "quietHours":{"enabled":true,"start":"20:00","end":"08:00","timezone":"Europe/London","fallbackChannel":"email"}}
```
Channels are `email`, `sms`, `phone`. The UI feed is implicit: every alert is visible to its team.

### 5.6 Alert (topic `alerts`, table `alerts`, API)
```json
{"alertId":"a-<16 hex>","alertKey":"<ruleId>|<16 hex of sha1(group values)>","ruleId":"...","ruleName":"...",
 "teamId":"...","sourceId":"...","schemaId":"...","status":"OPEN|ACKNOWLEDGED|RESOLVED",
 "severity":"error","color":"#d32f2f","title":"...","body":"...","fields":{"step":"validate-counterparty"},
 "groupValues":{"workflowName":"order-settlement"},"count":500,"firstSeen":0,"lastSeen":0,
 "lastEvent":{ ...original payload... },"type":"...","service":"...","runbookId":"...",
 "ackBy":null,"ackAt":null,"resolvedBy":null,"resolvedAt":null,"resolution":"manual|auto|null","note":null,
 "ingestTs":0,"updatedAt":0}
```
`alertId` = `"a-" + first 16 hex of sha1(alertKey + "|" + firstSeen)`.

### 5.7 AlertInput (matches repartition topic and `alert.commands`)
```json
{"kind":"MATCH","ruleId":"...","event":{...envelope...},"resolve":false}
{"kind":"COMMAND","alertId":"a-...","action":"ACK|RESOLVE","userId":"u-sam","note":"...","ts":0}
```
MATCH carries only `ruleId`; the alert processor looks the rule up in the config registry (a MATCH whose rule
has since been deleted or disabled is dropped).

### 5.8 Notification
```json
{"notificationId":"<sha1(alertId|userId|channel|seq)>","alertId":"...","alertKey":"...","ruleId":"...","teamId":"...",
 "userId":"...","channel":"email|sms|phone","address":"...","severity":"...","title":"...","body":"...",
 "runbookText":"...","link":"http://localhost:3000/alerts/<alertId>","ingestTs":0,"createdAt":0,
 "attempt":0,"notBefore":0}
```

### 5.9 Runbook
```json
{"id":"settlement-timeout","teamId":"settlements","name":"settlement-timeout","format":"yaml|markdown|text",
 "content":"<original text, never lost>","matches":{"type":"WORKFLOW_FAILED","error.code":"TIMEOUT"},
 "steps":[{"text":"Is counterparty-service healthy?","action":{"type":"http_get","params":{"url":"..."}},
           "approval":"none|required","condition":"retry fails twice"}],
 "version":1}
```
Import rules:
- YAML with a `steps` list: each item becomes a step. `check` or `if` text goes to `text`/`condition`; `action`
  names the action type; every other key becomes `params`; `approval` defaults to `none` for `http_get`, else `required`
  unless stated. `matches` is copied. `runbook` is the name. A free-text `condition` is shown to the human; it is not evaluated.
- Markdown: each numbered or bulleted list item becomes a text step; the first heading is the name.
- Anything else (including formats never seen): one text step containing the whole content. Always succeeds.

Runbook selection for an alert: `rule.runbookId`; else the runbook whose `matches` all equal the event's values
with the most keys; else full-text suggestions (top 3 with score) which a user confirms, which sets `rule.runbookId`.

### 5.10 Action types (table `action_types`, config not code)
```json
{"type":"retry_workflow","method":"POST","urlTemplate":"http://api:8080/mock/target/workflows/{{workflowId}}/retry",
 "bodyTemplate":"{\"workflowId\":\"{{workflowId}}\"}","defaultApproval":"none"}
```
`http_get` and `http_post` are built in and take `url` from step params. Templates see the alert's last event plus
step params. Target hosts must be on the allowlist (table `host_allowlist`, managed by a platform admin, each entry
global or scoped to a team). Seeded: `api`, `localhost`, `counterparty-service` (alias of the API mock).

## 6. Filter language

### 6.1 AST
```json
{"op":"and|or","args":[<node>,...]}
{"op":"not","arg":<node>}
{"op":"eq|ne|gt|ge|lt|le|like","field":"path","value":<string|number|boolean>}
{"op":"in","field":"path","values":[...]}
{"op":"between","field":"path","low":<v>,"high":<v>}
{"op":"isnull|notnull","field":"path"}
```
### 6.2 SQL subset (WHERE clause only, keywords case-insensitive)
```
expr    := or
or      := and ("OR" and)*
and     := unary ("AND" unary)*
unary   := "NOT" unary | "(" expr ")" | predicate
predicate := field ( ("="|"!="|"<>"|">"|">="|"<"|"<=") literal
                   | ["NOT"] "IN" "(" literal ("," literal)* ")"
                   | ["NOT"] "BETWEEN" literal "AND" literal
                   | ["NOT"] "LIKE" string
                   | "IS" ["NOT"] "NULL" )
field   := identifier ("." identifier)*      -- identifiers: [A-Za-z_@][A-Za-z0-9_@-]* ; or "double quoted"
literal := 'single quoted string' ('' escapes a quote) | number (underscores/commas not allowed) | TRUE | FALSE
```
`NOT IN/BETWEEN/LIKE` parse to `not` around the positive node. `LIKE` uses `%` and `_`; matching is case-sensitive.
A leading `WHERE` keyword is accepted and ignored. Empty input is the always-true filter `{"op":"and","args":[]}`.

### 6.3 Semantics
- A null/missing field makes any comparison false (`isnull` true). `not` simply negates.
- Number vs number: numeric. If either side is a number and the other a numeric string: numeric. Otherwise string compare.
- Field `severity` with ordering operators or `between` compares by severity order; literals are normalised first.
- `eq`/`in` on strings are case-sensitive, except on `severity` (normalised).

### 6.4 GUI compatibility
An AST is GUI-compatible when it is a single predicate or an `and` whose args are all predicates of kind
`eq`, `in`, `between`, `gt`, `ge`, `lt`, `le` (no `or`, `not`, `like`, nesting). The API reports
`guiCompatible`; when false the UI shows the "cannot be shown in the builder" warning and lets the user stay in SQL.

### 6.5 History queries
The same AST compiles to a parameterised Postgres predicate over table `alerts`: canonical fields map to columns
(`severity_rank` for ordered severity), other paths to `last_event #>> '{a,b}'`; numeric comparisons use a guarded
cast (`CASE WHEN x ~ '^-?[0-9.]+$' THEN x::numeric END`). Literals are always bind parameters.

## 7. Templates
`{{path}}` is replaced by the resolved value (section 5.3) rendered as text; objects render as compact JSON. An
unresolved placeholder renders as `⟦missing: path⟧` and is reported in `unresolved`. No logic, loops or helpers.
Whitespace inside braces is ignored. Default title: `{{type}} from {{service}}`; default body: empty.

## 8. Stream topology (`processor`, application.id `alerting-processor`)

Config: `processing.guarantee=exactly_once_v2`, `commit.interval.ms=100` (env `COMMIT_INTERVAL_MS`),
`num.stream.threads` from env (default 2), `replication.factor=3`, `metadata.max.age.ms=5000` on the main consumer,
`default.deserialization.exception.handler=LogAndContinue`.

```
cfg  ──(addGlobalStore "cfg-store")──► ConfigRegistry (compiled snapshots)

ingest.* (bytes) ─► NormaliseAndMatch processor ─► [AlertInput keyed by alertKey]
                           │ non-JSON ─► ingest.dlq
                           ▼
            repartition "matches" (6 partitions) ─┐
alert.commands ───────────────────────────────────┴─► AlertState processor (store "alert-state")
                                                          ├─► alerts          (key alertId)
                                                          └─► notifications   (key userId)
```

**ConfigRegistry** — process-wide singleton holding immutable snapshots: sources, schemas (sorted by priority),
rules indexed by `allowedSources`, teams, users, runbooks. Updated by the global store's state-update processor
on every `cfg` record, and rebuilt from `store.all()` in the `init` of the stream processors (covers restore, which
may bypass the update processor). Compiles filters once per rule version.

**NormaliseAndMatch** (stateless, one input record → zero or more outputs):
1. `sourceId` = topic name minus `ingest.`; unknown source → drop and count `events_unknown_source`.
2. Parse JSON. A JSON array is treated as multiple events. Failure → `ingest.dlq`.
3. Build the envelope (section 5.3). `ingestTs` = header `ingest-ts` if present, else the record timestamp.
4. For each enabled rule indexed under `sourceId`: if `filter` matches → emit MATCH (`resolve=false`); else if
   `resolveWhen` is set and matches → emit MATCH with `resolve=true`. Key = alertKey computed from `groupBy`.

**AlertState** (stateful, key alertKey, persistent store `alert-state` holding `AlertState` JSON):
State = current `Alert` (or null) plus `windowStart`, `windowCount`, `lastNotifiedAt`, `notifySeq`.
- MATCH, `resolve=false`:
  - If the rule has `window`: tumbling window aligned to the first event; increment `windowCount`; if the window
    expired, restart it at this event. While there is no active alert and `windowCount < window.count`, store and emit nothing.
  - No active alert (none, or status RESOLVED): open a new alert (`count`=1, or `windowCount` for windowed rules),
    render title/body/fields, emit to `alerts`, fan out notifications.
  - Active alert: `count++`, update `lastSeen`, `lastEvent`, re-render title/body, emit to `alerts`. Notify again only
    if `renotifyMinutes > 0` and that much time has passed since `lastNotifiedAt`.
- MATCH, `resolve=true`: if an active alert exists → status RESOLVED, `resolution=auto`, `resolvedBy="system"`, emit.
- COMMAND: ignored unless `alertId` equals the current alert's id. ACK: OPEN→ACKNOWLEDGED. RESOLVE: →RESOLVED
  (`resolution=manual`). Emit to `alerts`.
- Time source is the record timestamp (`context.currentSystemTimeMs()` only for `updatedAt`).

**Fan-out** (inside AlertState, pure CPU, using ConfigRegistry):
1. Recipients: for each rule target — `user` → that user; `oncall` → the team's `onCallUserId`; `team` → all members
   if `team.notify = all`, else only `onCallUserId`. De-duplicate users.
2. Channels per recipient: `rule.channels` if non-empty, else `user.channels[severity]`.
3. Quiet hours: if enabled and the current wall-clock time in the user's timezone is inside the window, replace all
   of that user's channels with `[fallbackChannel]`.
4. One Notification per (user, channel). `address` = email for `email`, phone for `sms`/`phone`. Skip if the address is empty.
   `runbookText` = rendered steps of the selected runbook (explicit or `matches`-based), `body` for SMS is the title plus link only.

## 9. Dispatcher

Plain consumers, group `dispatcher`, `enable.auto.commit=false`, `isolation.level=read_committed`, two threads:
one on `notifications`, one on `notifications.retry`.

Per record: `INSERT INTO deliveries(notification_id, …, status='PENDING') ON CONFLICT DO NOTHING`; if the existing
row is `SENT`, skip (idempotency). Call the channel adapter. On success update to `SENT` with `latency_ms = now - ingestTs`.
On failure: `attempt+1`; if `attempt < 5` produce to `notifications.retry` with `notBefore = now + backoff`
(1s, 5s, 15s, 60s) and status `RETRYING`; else produce to `notifications.dlq`, status `FAILED`. Commit after each batch.
The retry thread pauses until `notBefore`.

Channel adapter interface (the only thing a real provider implements):
```java
public interface ChannelAdapter { String channel(); void send(Notification n) throws Exception; }
```
Adapters: `EmailAdapter` (SMTP to `SMTP_HOST:SMTP_PORT`, default mailpit:1025; body includes fields, link and
`runbookText`), `SmsAdapter` and `PhoneAdapter` (mock: log line + delivery row; SMS text is `title` + link, max 160 chars).
Failure injection for the demo: table `channel_status(channel, down)`; an adapter throws when its channel is down.
The adapter list is built in `ChannelAdapters.all()`; adding a provider is one class plus one line there.

## 10. API

Javalin, JSON, port 8080. `Authorization: Bearer <jwt>` on everything under `/api` except `/api/auth/*`.
JWT: HS256, secret env `JWT_SECRET`, claims `sub` (userId), `exp` (+12h). SSE accepts `?token=`.
Errors: `{"error":"message"}` with 400 (validation), 401, 403 (RBAC), 404.

### 10.1 RBAC rules
- `platformAdmin`: everything.
- Team `admin`: create/edit/delete that team's sources, schemas, rules, runbooks, team policy, source grants.
- Team `member`: read that team's resources and alerts; ack/resolve alerts; execute runbook steps with
  `approval=none`; manage own preferences and personal saved filters.
- Approving a step with `approval=required`: team admin of the alert's team.
- Nobody outside a team sees its alerts, rules or sources. Reading another team's source in a rule requires a grant
  on that source (given by the source's team admin).
- Every mutation and every denied attempt is written to `audit_log`.

### 10.2 Endpoints
```
POST /api/auth/login {username}                -> {token, user}
GET  /api/auth/users                           -> [{id, username, displayName}]          (mock login screen)
GET  /api/me                                   -> user + teams:[{teamId, teamName, role}]
GET/PUT /api/me/preferences                    -> {email, phone, channels, quietHours}

GET  /api/teams                                -> teams the caller belongs to (all for platform admin), with members
PUT  /api/teams/{id}/policy {notify, onCallUserId}

GET  /api/sources | POST /api/sources {name, teamId, type, defaultSchemaId} | DELETE /api/sources/{id}
POST /api/sources/{id}/grants {teamId} | DELETE /api/sources/{id}/grants/{teamId}

GET  /api/schemas | POST | PUT /api/schemas/{id} | DELETE
POST /api/schemas/infer {sample:<json>}        -> {fields:[{path,type,example}], mapping:{...suggested...}}

GET  /api/rules                                -> [{...rule, canEdit, teamName}]
POST /api/rules | PUT /api/rules/{id} | DELETE /api/rules/{id}
     body may carry filterSql (parsed server-side) or filter (AST); the other is derived.
POST /api/rules/preview {rule, sample:<json>, sourceId?} -> {matches, severity, title, body, fields, unresolved, alertKey}

POST /api/filters/parse {sql}                  -> {ast, guiCompatible, sql:<canonical>}   400 with position on error
POST /api/filters/print {ast}                  -> {sql, guiCompatible}
GET  /api/fields                               -> [{path, type, example, schemaId}] canonical + all schema fields
GET  /api/saved-filters | POST {name, scope:"personal|team", teamId?, sql} | DELETE /api/saved-filters/{id}

GET  /api/alerts?teamId=&status=&filter=<sql>&limit=100&before=<lastSeen>  -> [Alert]
GET  /api/alerts/{id}                          -> Alert
POST /api/alerts/{id}/ack | POST /api/alerts/{id}/resolve {note}           -> 202
GET  /api/alerts/stream?token=&teamId=         -> SSE, event "alert", data Alert JSON
GET  /api/alerts/{id}/deliveries               -> [delivery rows]
GET  /api/alerts/{id}/audit                    -> [audit rows]

GET  /api/runbooks | POST {teamId, content, format?, name?} | PUT /api/runbooks/{id} | DELETE
POST /api/runbooks/parse {content, format?}    -> parsed runbook preview (not saved)
GET  /api/alerts/{id}/runbook                  -> {runbook|null, steps:[{index,text,condition,action,approval,
                                                    status,result,unresolved}], suggestions:[{runbookId,name,score}]}
POST /api/alerts/{id}/runbook/link {runbookId} -> sets rule.runbookId (team admin)
POST /api/alerts/{id}/runbook/steps/{index}/execute -> runs the action, or creates a pending approval
POST /api/alerts/{id}/runbook/steps/{index}/approve | /reject

GET  /api/action-types | POST (platform admin)
GET  /api/admin/channels | PUT /api/admin/channels/{channel} {down}   (platform admin; failure injection)

POST /ingest/{sourceId}   header X-Source-Token; body: JSON object or array -> 202 {accepted:n}
ANY  /mock/target/*       logs the call, returns 200 {"ok":true}; /mock/target/fail/* returns 500
GET  /healthz | /readyz | /metrics
```

### 10.3 Background work in the API role
- **Config publisher**: after each committed config write, produce the JSON to `cfg` (`acks=all`, idempotent producer).
  On startup: create topics, run migrations, seed if empty, republish everything.
- **Alert projector**: consumer group `alert-projector` on `alerts` (`read_committed`). Per poll batch keep only the
  last record per alertId, then upsert into `alerts`. Status transitions write `audit_log`.
- **SSE broadcaster**: consumer with a unique group id per API instance on `alerts` (start at latest), coalesced per
  poll batch, pushed to connected clients whose user belongs to the alert's team.

### 10.4 Postgres tables
`users`, `teams`, `team_members(team_id,user_id,role)`, `sources`, `source_grants(source_id,team_id)`,
`schemas`, `rules`, `runbooks`, `action_types`, `host_allowlist(host,team_id null)`, `saved_filters`,
`alerts` (columns for id, key, rule, team, source, schema, status, severity, severity_rank, type, service, title,
count, first_seen, last_seen, updated_at, `doc jsonb` = full Alert, `last_event jsonb`),
`deliveries(notification_id pk, alert_id, user_id, channel, address, status, attempt, latency_ms, error, created_at, updated_at)`,
`runbook_steps(alert_id, step_index, status, result, requested_by, approved_by, updated_at)`,
`audit_log(id, ts, user_id, action, resource_type, resource_id, alert_id, detail jsonb)`,
`channel_status(channel pk, down)`.
Config tables store the entity as `doc jsonb` plus the columns needed for lookups (`id`, `team_id`).

## 11. Seed data
Teams: `settlements`, `pricing`, `rates`, `payments`, plus platform admin user `admin`.
Users: `sam` (settlements admin, on-call), `sara` (settlements member), `petra` (pricing admin), `ravi` (rates admin),
`anna` (payments admin, quiet hours 20:00–08:00 fallback email, critical→sms), `omar` (payments member).
Sources: `settlement-bus` (kafka, settlements), `metrics-webhook` (webhook, payments), `trade-gateway` (kafka, rates),
`pricing-logs` (webhook, pricing).
Schemas: `workflow-failed`, `alertmanager`, `trade-rejected` (match `meta.schema LIKE 'trade.rejected.%'`).
Rules: the four example rules from Appendix A of the intention document.
Runbooks: the three from Appendix B (`settlement-timeout` attached to the settlements rule, the markdown page
attached to the payments rule, the free text unattached so it is found by search).

## 12. UI (see `ui/`)
Routes: `/login` (pick a user), `/` alert feed with filter panel (GUI/SQL toggle, saved filters, live SSE updates,
ack/resolve), `/alerts/:id` detail (fields, counter, runbook steps with execute/approve, deliveries, audit),
`/sources` (sources + schemas, "paste a sample" onboarding, grants), `/rules` (list grouped by team, editor with
filter builder, routing, presentation, live preview against a sample), `/runbooks`, `/preferences`, `/admin`
(team policy, channel failure injection). Non-engineer first: every basic task is doable with form controls.

## 13. Benchmark
`ROLE=bench`: produces `BENCH_EVENTS` events at `BENCH_RATE`/s to `ingest.<source>` with `ingest-ts` headers, mixing
the three sample schemas with configurable cardinality of group keys, while consuming `alerts` and `notifications`
(`read_committed`) to compute ingest→alert and ingest→notification latency (p50/p95/p99) and throughput. Prints a
table and writes `bench/results/<timestamp>.json`. Run: `docker compose --profile bench run --rm bench`.

## 14. Not built (documented cut line)
Kafka SASL/ACLs per source topic; archive to object storage; Kubernetes manifests; approval by SMS reply;
LLM extraction of actions from prose and runbook auto-learning (interface `LlmProvider` exists, no-op);
embedding search (interface `RunbookSearch`, implemented with Postgres full-text search); change proposals with
diffs for rules/runbooks; per-recipient rate limiting beyond per-alert dedup and `renotifyMinutes`;
tailed-log-file source and regex line parsing; event-id de-duplication of retried webhook posts;
transactional outbox (replaced by publish-after-commit plus republish on start).

## 15. Deviations recorded during implementation
These supersede the sections above where they differ.

- **Config storage (10.4):** one table `config(kind, id, team_id, doc jsonb)` holds sources, schemas, rules, teams,
  users and runbooks. Team members live in the team document; source grants in `source.grantedTeamIds`.
- **Matches topic (4, 8):** an explicit topic `alerting.matches` (6 partitions) is used instead of an automatic
  repartition topic, and the topology is built with the Processor API (`AlertTopology.build`).
- **Alert id (5.6):** `"a-" + first 16 hex of sha1(alertKey | firstSeen | eventId of the opening event)`.
- **UI-only routing:** a rule with `channels: ["ui"]` sends no external notification.
- **Rule save (10.2):** `filter` (AST) wins over `filterSql` when both are sent; likewise `resolveWhen` over
  `resolveWhenSql`. Schemas accept `matchSql` or `match`.
- **Schemas are readable by every signed-in user** (structure only); only the owning team's admins edit them.
- **`GET /api/teams`** returns full documents for the caller's teams and `{id, name}` for the others.
  **`GET /api/users`** returns `{id, username, displayName}` for target pickers.
- **Runbook step status values:** `NOT_RUN`, `PENDING_APPROVAL`, `DONE`, `FAILED`, `REJECTED`. A team admin who
  runs an approval-required step approves it by doing so.
- **Action host mapping:** env `ACTION_HOST_MAP` (default `counterparty-service=localhost:8080`) redirects sample
  hosts to the built-in mock target. The allowlist is still checked against the original host.
- **SSE** requires the `Accept: text/event-stream` header (browsers send it).
- **Postgres is not published to the host.** Processor and dispatcher health ports are internal to the compose network.
- **Benchmark** writes to `bench/results/<runId>.json` and tallies per-alert counter growth so that it can run
  against a stack that already has open alerts.
- **Private rules:** `POST /api/rules` accepts `"private": true`. Any member of `teamId` may create one; the server
  sets `ownerUserId` to the caller and forces `targets` to that user. The rule still reads only the sources its team
  may read. Its alerts carry `ownerUserId` and are visible only to the owner and platform admins (list, detail,
  live feed). The owner edits and deletes it; team admins do not see it. Team rules have `ownerUserId: null`.
- **Config snapshot (stream):** a reader never receives the initial empty snapshot. Before the first build readers
  wait; afterwards one thread rebuilds while the others keep the previous snapshot, and re-applying an unchanged
  document does not trigger a rebuild. (Found by the benchmark: events were dropped at processor start-up when
  several stream threads raced for the first snapshot.)
- **Dispatcher concurrency:** each poll batch is grouped by recipient (record key); groups are sent in parallel
  (`DISPATCH_CONCURRENCY`, default 8), each group in order. Offsets are committed after the whole batch, so delivery
  stays at-least-once with the Postgres idempotency key.
- **Theme:** user preference `theme` (`light` | `dark`) on `GET/PUT /api/me/preferences`.
- **Benchmark (13):** capacity is measured by a burst into running processors (`BENCH_DRAIN=true`, see
  `scripts/bench_scaling.py`); ingest → delivered latency is read from the `deliveries` table. Core limits use
  dedicated cores (`docker update --cpuset-cpus`); CPU quotas (`--cpus`) were tried and rejected because CFS
  throttling stalls exactly-once commits and distorts the result.
- **Input formats:** `core/Formats.java` turns a body into events: JSON (object or array), NDJSON, CSV/TSV with a
  header row, YAML (multi-document), XML (attributes and children become fields, a root of repeated children is a
  batch; DOCTYPE/external entities refused), logfmt, syslog (RFC 5424 and 3164), form fields and plain text (one event
  per line, level guessed from words such as ERROR). Values are typed (numbers, booleans), dotted keys become nested
  fields. Source field `format` (default `auto`) is strict when set; a `Content-Type` is a hint that falls back to
  detection (`application/x-www-form-urlencoded` is ignored because `curl -d` sends it for everything). Only non-text
  bodies go to `ingest.dlq`. The webhook answers `{"accepted": <events>, "format": <detected>}`. `POST
  /api/formats/parse {text, format?}` shows how a sample is read; schema inference and rule preview accept a sample
  in any format.
- **Field catalog:** `GET /api/fields?sourceIds=a,b` returns built-in fields (with `scope` `event` or `alert`), the
  fields of the schemas those sources use (default schema plus schemas their recent events were recognised as) and
  the fields of their last ~50 events read from Kafka, each with `type`, `example`, `origin`
  (`built-in` | `schema` | `observed`) and, for text fields with at most 12 distinct values, `values`. Sources the
  caller may not read contribute nothing. The rule editor offers only these fields (plus a pasted sample's), with
  typed operators and observed values as choices.
- **Builder join:** the builder holds a flat list joined by AND ("all of") or OR ("any of"); mixed nesting stays
  in SQL mode, which is checked as it is typed.
- **Identity:** demo login only; production SSO would replace `Auth.load` with token verification plus a
  group → team/role mapping (README, "Identity and teams").
- **Incident → runbook draft:** `GET /api/alerts/{id}/runbook-draft` returns `{name, teamId, content (YAML),
  stepsFromRunbook, stepsFromNotes, basedOn}` built from the alert's audit trail: runbook steps that were run (or
  approved, or marked done) are copied from their templates; rejected steps are kept with a comment; ack/resolve notes
  become text steps with distinctive event values replaced by `{{path}}` placeholders (longest value first). `matches`
  is the alert's type and service. The UI opens it in the runbook editor as an unsaved draft (team admins only).
- **HTTP polling sources:** source `type: "poll"` with `poll: {url, intervalSeconds (>= 5), onlyWhenChanged
  (default true)}`; the host must be on `host_allowlist` (checked on save and on every fetch). ROLE `poller` reads
  poll sources from the `config` table every second, fetches due ones (virtual threads, 5 s timeout, no redirects)
  and produces the body to `ingest.<id>` with `ingest-ts` and the response `content-type`. Unchanged bodies (SHA-1)
  are skipped when `onlyWhenChanged`. The first failure sends `{"type":"POLL_FAILED","severity":"error",...}`, the
  first success after failures `POLL_RECOVERED`. Status per source in table `poll_status`, shown as `pollStatus` on
  `GET /api/sources`. `POLLER_SHARDS` / `POLLER_INDEX` split sources between poller instances; `POLL_HOST_MAP`
  (default `localhost=api` in compose) rewrites hosts for the compose network. Seed: `pricing-status` polls
  `/mock/status/pricing-engine` every 10 s, rule `pricing-status-down`.
- **Assistant (optional):** `GET /api/assistant` → `{enabled, model}`; `POST /api/filters/from-text {text, scope:
  "rule"|"alerts", sourceIds?}` → `{sql, ast, guiCompatible, unknownFields, model}`. Enabled only when
  `ANTHROPIC_API_KEY` is set on the API role (503 otherwise). The prompt holds the filter grammar, the field catalog
  for the given sources (names and types; values only for built-in fields) and the description. The reply is cleaned
  (code fence, leading WHERE, trailing semicolon), parsed with `SqlParser`, and on a parse error the model gets one
  correction attempt with the error message; then 422. `CANNOT: reason` replies become 422 with the reason.
  Provider interface `Assistant.Llm`; default uses the Anthropic Java SDK (`LLM_MODEL`, `LLM_EFFORT`).
