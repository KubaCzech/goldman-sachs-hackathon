#!/usr/bin/env python3
"""End-to-end smoke test against the running compose stack (gates for plan phases 3, 4 and 6).

Usage: python3 scripts/smoke.py [api-base-url]      (default http://localhost:8080)
Exits non-zero on the first failed check.
"""
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
MAILPIT = "http://localhost:8025"
RUN = format(int(time.time()), "x")
passed = 0


def call(method, path, token=None, body=None, headers=None, base=API):
    data = None if body is None else (body if isinstance(body, bytes) else json.dumps(body).encode())
    req = urllib.request.Request(base + path, data=data, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw.decode(errors="replace")


def check(name, cond, detail=""):
    global passed
    if not cond:
        print(f"FAIL  {name}  {detail}")
        sys.exit(1)
    passed += 1
    print(f"ok    {name}")


def login(user):
    status, body = call("POST", "/api/auth/login", body={"username": user})
    assert status == 200, body
    return body["token"]


def ingest(source, event):
    return call("POST", f"/ingest/{source}", body=event, headers={"X-Source-Token": "demo-" + source})[0]


def wait_for(fn, seconds=15):
    end = time.time() + seconds
    while time.time() < end:
        v = fn()
        if v:
            return v
        time.sleep(0.3)
    return None


def alerts(token, flt=None, status=None):
    q = {"limit": "200"}
    if flt:
        q["filter"] = flt
    if status:
        q["status"] = status
    s, body = call("GET", "/api/alerts?" + urllib.parse.urlencode(q), token)
    assert s == 200, body
    return body


anna, omar, sam, sara, ravi, petra, admin = (login(u) for u in ["anna", "omar", "sam", "sara", "ravi", "petra", "admin"])

# ---------------------------------------------------------------- ingest, dedup, RBAC on alerts
region = "smoke-" + RUN
metric = {"alertname": "HighErrorRate", "labels": {"service": "payments-api", "region": region, "severity": "critical"},
          "annotations": {"summary": "Error rate 12% over the last 5 minutes"}, "startsAt": "2026-10-23T09:13:00Z", "status": "firing"}
check("webhook rejects a wrong token", call("POST", "/ingest/metrics-webhook", body=metric, headers={"X-Source-Token": "nope"})[0] == 401)
check("webhook accepts an event", ingest("metrics-webhook", metric) == 202)
mine = lambda: [a for a in alerts(anna) if a.get("groupValues", {}).get("labels.region") == region]
got = wait_for(mine, 10)
check("alert appears for the owning team within 10 s", bool(got))
alert = got[0]
check("title is rendered from the rule template", alert["title"] == f"HighErrorRate on payments-api ({region})", alert["title"])
check("severity is normalised", alert["severity"] == "critical")
check("another team does not see it in its feed", not [a for a in alerts(ravi) if a["alertId"] == alert["alertId"]])
check("another team gets 403 on the alert itself", call("GET", "/api/alerts/" + alert["alertId"], ravi)[0] == 403)
check("no token gets 401", call("GET", "/api/alerts")[0] == 401)

for _ in range(60):
    ingest("metrics-webhook", metric)
grown = wait_for(lambda: [a for a in mine() if a["count"] == 61], 15)
check("61 identical events are one alert with count 61", bool(grown) and len(mine()) == 1, str([(a["alertId"], a["count"]) for a in mine()]))

deliv = wait_for(lambda: [d for d in call("GET", f"/api/alerts/{alert['alertId']}/deliveries", anna)[1] if d["status"] == "SENT"], 15)
check("exactly one notification was delivered for 61 events", bool(deliv) and len(call("GET", f"/api/alerts/{alert['alertId']}/deliveries", anna)[1]) == 1, str(deliv))
check("delivery records a measured latency", deliv[0]["latencyMs"] is not None and deliv[0]["latencyMs"] >= 0)

check("a team member can acknowledge", call("POST", f"/api/alerts/{alert['alertId']}/ack", omar, {})[0] == 202)
check("status becomes ACKNOWLEDGED", bool(wait_for(lambda: [a for a in mine() if a["status"] == "ACKNOWLEDGED" and a["ackBy"] == "u-omar"])))
ingest("metrics-webhook", dict(metric, status="resolved"))
check("a resolve event closes the alert automatically", bool(wait_for(lambda: [a for a in mine() if a["status"] == "RESOLVED" and a["resolution"] == "auto"])))

# ---------------------------------------------------------------- filters on history
trade_id = "T-" + RUN
trade = {"meta": {"schema": "trade.rejected.v2", "producer": "trade-gateway", "ts": 1792833245000},
         "payload": {"tradeId": trade_id, "desk": "rates", "notional": 25000000, "currency": "EUR", "reason": "LIMIT_BREACH"}}
ingest("trade-gateway", trade)
t_alert = wait_for(lambda: alerts(ravi, f"payload.tradeId = '{trade_id}'"))
check("history filter on a nested body value finds the alert", bool(t_alert))
check("title uses the Appendix A template", t_alert[0]["title"] == "Rejected EUR 25000000 — LIMIT_BREACH", t_alert[0]["title"])
check("numeric range filter matches", len(alerts(ravi, f"payload.tradeId = '{trade_id}' AND payload.notional BETWEEN 10000000 AND 30000000")) == 1)
check("numeric filter excludes", len(alerts(ravi, f"payload.tradeId = '{trade_id}' AND payload.notional > 99999999")) == 0)
check("IN list and OR work", len(alerts(ravi, f"payload.tradeId = '{trade_id}' AND (payload.currency IN ('USD','EUR') OR severity = 'critical')")) == 1)
s, parsed = call("POST", "/api/filters/parse", ravi, {"sql": "a = 1 AND b IN ('x','y') AND c BETWEEN 1 AND 5"})
check("AND-of-fields filter is builder compatible", s == 200 and parsed["guiCompatible"] is True)
s, parsed = call("POST", "/api/filters/parse", ravi, {"sql": "a = 1 OR b = 2"})
check("OR filter is flagged as not builder compatible", s == 200 and parsed["guiCompatible"] is False)
s, err = call("POST", "/api/filters/parse", ravi, {"sql": "a = "})
check("invalid SQL gives 400 with a position", s == 400 and "position" in err, str(err))
s, err = call("GET", "/api/alerts?" + urllib.parse.urlencode({"filter": "x = 1; DROP TABLE alerts"}), ravi)
check("injection attempt in a filter is rejected by the parser", s == 400)

mail = wait_for(lambda: [m for m in call("GET", "/api/v1/search?" + urllib.parse.urlencode({"query": "LIMIT_BREACH"}), base=MAILPIT)[1].get("messages", [])], 15)
check("email arrives in the mock mailbox", bool(mail))

# ---------------------------------------------------------------- channel failure: retry, no loss, no duplicate
check("only a platform admin can inject a channel failure", call("PUT", "/api/admin/channels/email", ravi, {"down": True})[0] == 403)
check("platform admin marks email down", call("PUT", "/api/admin/channels/email", admin, {"down": True})[0] == 200)
time.sleep(1.5)
trade2 = json.loads(json.dumps(trade))
trade2["payload"]["tradeId"] = trade_id + "-B"
ingest("trade-gateway", trade2)
t2 = wait_for(lambda: alerts(ravi, f"payload.tradeId = '{trade_id}-B'"))
check("alert still opens while the channel is down", bool(t2))
dl = lambda: call("GET", f"/api/alerts/{t2[0]['alertId']}/deliveries", ravi)[1]
check("delivery is retrying, not lost", bool(wait_for(lambda: [d for d in dl() if d["status"] == "RETRYING"], 15)), str(dl()))
call("PUT", "/api/admin/channels/email", admin, {"down": False})
check("delivery succeeds after the channel recovers", bool(wait_for(lambda: [d for d in dl() if d["status"] == "SENT"], 40)), str(dl()))
check("exactly one delivery row (no duplicate send)", len(dl()) == 1)

# ---------------------------------------------------------------- rules at runtime and RBAC on configuration
rule = {"teamId": "rates", "name": "smoke fx " + RUN, "filterSql": f"payload.desk = 'fx-{RUN}' AND payload.notional > 1",
        "sourceIds": ["trade-gateway"], "groupBy": ["payload.tradeId"], "targets": [{"type": "team", "id": "rates"}], "channels": ["ui"],
        "presentation": {"titleTemplate": "FX {{payload.tradeId}} {{nope.missing}}", "bodyTemplate": "", "fields": [], "color": "#00897b"}}
check("a member (not admin) cannot create a team rule", call("POST", "/api/rules", sara, dict(rule, teamId="settlements"))[0] == 403)
check("a user cannot create a rule for another team", call("POST", "/api/rules", sam, rule)[0] == 403)
s, created = call("POST", "/api/rules", ravi, rule)
check("team admin creates a rule at runtime", s == 201 and created["allowedSources"] == ["trade-gateway"], str(created))
fx = json.loads(json.dumps(trade))
fx["payload"].update(tradeId="FX-" + RUN, desk="fx-" + RUN)
s, pv = call("POST", "/api/rules/preview", ravi, {"rule": rule, "sample": fx})
check("preview matches and reports the missing placeholder", s == 200 and pv["matches"] and pv["unresolved"] == ["nope.missing"], str(pv))
time.sleep(1)
ingest("trade-gateway", fx)
fxa = wait_for(lambda: alerts(ravi, f"payload.tradeId = 'FX-{RUN}'"))
check("the new rule fires without any restart", bool(fxa) and fxa[0]["color"] == "#00897b", str(fxa))
check("rule is deleted", call("DELETE", "/api/rules/" + created["id"], ravi)[0] == 204)

s, nosy = call("POST", "/api/rules", sam, {"teamId": "settlements", "name": "nosy " + RUN, "filterSql": "payload.notional > 1", "sourceIds": ["trade-gateway"],
                                            "targets": [{"type": "team", "id": "settlements"}], "channels": ["ui"]})
check("a rule on another team's source gets no access by default", s == 201 and nosy["allowedSources"] == [], str(nosy))
check("only the source's team admin can grant", call("POST", "/api/sources/trade-gateway/grants", sam, {"teamId": "settlements"})[0] == 403)
check("owner grants read access", call("POST", "/api/sources/trade-gateway/grants", ravi, {"teamId": "settlements"})[0] == 200)
after = [r for r in call("GET", "/api/rules", sam)[1] if r["id"] == nosy["id"]][0]
check("the grant propagates into the rule", after["allowedSources"] == ["trade-gateway"])
call("DELETE", "/api/sources/trade-gateway/grants/settlements", ravi)
after = [r for r in call("GET", "/api/rules", sam)[1] if r["id"] == nosy["id"]][0]
check("revoking the grant removes access again", after["allowedSources"] == [])
call("DELETE", "/api/rules/" + nosy["id"], sam)

# ---------------------------------------------------------------- any input format
def raw(source, text, content_type=None):
    h = {"X-Source-Token": "demo-" + source}
    if content_type:
        h["Content-Type"] = content_type
    return call("POST", f"/ingest/{source}", body=text.encode() if isinstance(text, str) else text, headers=h)

fmt_ids = {f: f"F{f.upper()}-{RUN}" for f in ["csv", "yaml", "xml", "logfmt", "ndjson"]}
bodies = {
    "csv": "meta.schema,payload.tradeId,payload.desk,payload.notional,payload.currency,payload.reason\n"
           f"trade.rejected.v2,{fmt_ids['csv']},rates,25000000,EUR,LIMIT_BREACH\n",
    "yaml": f"meta:\n  schema: trade.rejected.v2\npayload:\n  tradeId: {fmt_ids['yaml']}\n  desk: rates\n  notional: 25000000\n  currency: EUR\n  reason: LIMIT_BREACH\n",
    "xml": f"<trade><meta><schema>trade.rejected.v2</schema></meta><payload><tradeId>{fmt_ids['xml']}</tradeId><desk>rates</desk>"
           "<notional>25000000</notional><currency>EUR</currency><reason>LIMIT_BREACH</reason></payload></trade>",
    "logfmt": f"meta.schema=trade.rejected.v2 payload.tradeId={fmt_ids['logfmt']} payload.desk=rates payload.notional=25000000 payload.currency=EUR payload.reason=LIMIT_BREACH",
    "ndjson": json.dumps({"meta": {"schema": "trade.rejected.v2"}, "payload": {"tradeId": fmt_ids["ndjson"], "desk": "rates", "notional": 25000000, "currency": "EUR", "reason": "LIMIT_BREACH"}})
              + "\n" + json.dumps({"meta": {"schema": "trade.rejected.v2"}, "payload": {"tradeId": fmt_ids["ndjson"] + "-2", "desk": "rates", "notional": 25000000, "currency": "EUR", "reason": "LIMIT_BREACH"}}),
}
for f, body in bodies.items():
    s, r = raw("trade-gateway", body)          # no Content-Type: urllib sends a form type, which must not mislead detection
    check(f"webhook reads {f} and says so", s == 202 and r["format"] == f and r["accepted"] == (2 if f == "ndjson" else 1), str(r))
for f in bodies:
    got = wait_for(lambda f=f: alerts(ravi, f"payload.tradeId = '{fmt_ids[f]}'"))
    check(f"a {f} event raises the same alert as JSON would", bool(got) and got[0]["fields"].get("payload.reason") == "LIMIT_BREACH", str(got)[:200])
s, r = raw("trade-gateway", "just a line of text with ERROR in it")
check("plain text is accepted as an event", s == 202 and r["format"] == "text")
check("a binary body is refused with a clear message", raw("trade-gateway", bytes([0, 1, 2, 3, 255, 254, 0, 9]))[0] == 400)
s, r = call("POST", "/api/formats/parse", ravi, {"text": "level=error service=pricing msg=\"cache miss\""})
check("the parse endpoint shows how a sample is read", s == 200 and r["format"] == "logfmt" and r["events"][0]["msg"] == "cache miss", str(r))

# ---------------------------------------------------------------- private rules (a member's own rule)
mine_rule = {"teamId": "settlements", "private": True, "name": "sara private " + RUN, "filterSql": f"workflowName = 'priv-{RUN}'",
             "sourceIds": ["settlement-bus"], "groupBy": ["workflowName"], "targets": [{"type": "team", "id": "settlements"}]}
s, pr = call("POST", "/api/rules", sara, mine_rule)
check("a member can create a private rule", s == 201 and pr["ownerUserId"] == "u-sara", str(pr))
check("a private rule notifies only its owner", s == 201 and pr["targets"] == [{"type": "user", "id": "u-sara"}], str(pr.get("targets")))
check("the owner sees it with edit rights", [r["canEdit"] for r in call("GET", "/api/rules", sara)[1] if r["id"] == pr["id"]] == [True])
check("the team admin does not see someone's private rule", not [r for r in call("GET", "/api/rules", sam)[1] if r["id"] == pr["id"]])
check("the team admin cannot delete it", call("DELETE", "/api/rules/" + pr["id"], sam)[0] == 403)
time.sleep(1)
ingest("settlement-bus", {"type": "WORKFLOW_FAILED", "workflowId": "WF-P-" + RUN, "workflowName": "priv-" + RUN,
                                                "severity": "ERROR", "environment": "prod", "timestamp": "2026-10-23T09:14:02.331Z"})
pa_alert = wait_for(lambda: alerts(sara, f"workflowName = 'priv-{RUN}'"))
check("the private rule's alert reaches its owner", bool(pa_alert) and pa_alert[0].get("ownerUserId") == "u-sara", str(pa_alert))
check("the rest of the team does not see that alert", not alerts(sam, f"workflowName = 'priv-{RUN}'"))
check("the alert itself is hidden from the team admin", bool(pa_alert) and call("GET", "/api/alerts/" + pa_alert[0]["alertId"], sam)[0] == 404)
check("the owner deletes the private rule", call("DELETE", "/api/rules/" + pr["id"], sara)[0] == 204)

# ---------------------------------------------------------------- unseen schema on a new source
s, src = call("POST", "/api/sources", petra, {"teamId": "pricing", "name": "smoke " + RUN, "type": "webhook", "defaultSchemaId": None})
check("team admin creates a source", s == 201 and src.get("token"), str(src))
odd = {"evt": {"kind": "DISK_FULL", "node": "n-" + RUN}, "lvl": "fatal-ish", "pct": 97.5}
s, inf = call("POST", "/api/schemas/infer", petra, {"sample": odd})
check("schema inference lists nested fields", s == 200 and {"evt.kind", "evt.node", "pct"} <= {f["path"] for f in inf["fields"]})
s, r2 = call("POST", "/api/rules", petra, {"teamId": "pricing", "name": "smoke disk " + RUN, "filterSql": "evt.kind = 'DISK_FULL' AND pct >= 90", "sourceIds": [src["id"]],
                                           "groupBy": ["evt.node"], "severity": "critical", "targets": [{"type": "team", "id": "pricing"}], "channels": ["ui"],
                                           "presentation": {"titleTemplate": "Disk full on {{evt.node}} ({{pct}}%)"}})
check("rule on a never-registered schema is accepted", s == 201, str(r2))
time.sleep(8)   # the processor notices the new ingest topic on its next metadata refresh (5 s)
call("POST", f"/ingest/{src['id']}", body=odd, headers={"X-Source-Token": src["token"]})
da = wait_for(lambda: alerts(petra, f"evt.node = 'n-{RUN}'"), 30)
check("an event with a schema never seen before produces an alert", bool(da) and da[0]["title"] == f"Disk full on n-{RUN} (97.5%)" and da[0]["schemaId"] == "unknown", str(da))
call("DELETE", "/api/rules/" + r2["id"], petra)
call("DELETE", "/api/sources/" + src["id"], petra)

# ---------------------------------------------------------------- runbooks: match, fill, act, approve, audit
wf_id = "WF-" + RUN
wf = {"type": "WORKFLOW_FAILED", "workflowId": wf_id, "workflowName": "order-settlement", "step": "validate-counterparty", "severity": "ERROR",
      "environment": "prod", "timestamp": "2026-10-23T09:14:02.331Z", "error": {"code": "TIMEOUT", "message": "Counterparty service did not respond in 5000 ms"}}
ingest("settlement-bus", wf)
wa = wait_for(lambda: alerts(sam, f"workflowId = '{wf_id}'"))
check("workflow failure alert opens", bool(wa))
aid = wa[0]["alertId"]
s, rb = call("GET", f"/api/alerts/{aid}/runbook", sara)
check("the settlement-timeout runbook is attached", s == 200 and rb["runbook"]["id"] == "settlement-timeout" and len(rb["steps"]) == 3, str(rb)[:300])
check("placeholders are filled with values from this alert", rb["steps"][1]["action"]["params"]["workflowId"] == wf_id and wf_id in rb["steps"][1]["text"], str(rb["steps"][1]))
s, rb = call("POST", f"/api/alerts/{aid}/runbook/steps/0/execute", sara, {})
check("a member runs the health-check action", s == 200 and rb["steps"][0]["status"] == "DONE" and "HTTP 200" in rb["steps"][0]["result"], str(rb["steps"][0]) if s == 200 else str(rb))
s, rb = call("POST", f"/api/alerts/{aid}/runbook/steps/1/execute", sara, {})
check("a configured action type (retry_workflow) runs with the workflow id", s == 200 and rb["steps"][1]["status"] == "DONE" and wf_id in rb["steps"][1]["result"], str(rb)[:300])
s, rb = call("POST", f"/api/alerts/{aid}/runbook/steps/2/execute", sara, {})
check("a risky action waits for approval when a member triggers it", s == 200 and rb["steps"][2]["status"] == "PENDING_APPROVAL", str(rb)[:300])
check("a member cannot approve", call("POST", f"/api/alerts/{aid}/runbook/steps/2/approve", sara, {})[0] == 403)
s, rb = call("POST", f"/api/alerts/{aid}/runbook/steps/2/approve", sam, {})
check("the team admin approves and the action runs", s == 200 and rb["steps"][2]["status"] == "DONE" and rb["steps"][2]["approvedBy"] == "u-sam", str(rb)[:300])
actions = [a["action"] for a in call("GET", f"/api/alerts/{aid}/audit", sam)[1]]
check("audit trail records who did what", {"ALERT_OPENED", "STEP_RUN", "STEP_APPROVAL_REQUESTED", "STEP_APPROVED_AND_RUN"} <= set(actions), str(actions))
call("POST", f"/api/alerts/{aid}/resolve", sam, {"note": "smoke test"})
check("manual resolve with a note", bool(wait_for(lambda: [a for a in alerts(sam, f"workflowId = '{wf_id}'") if a["status"] == "RESOLVED" and a["note"] == "smoke test"])))

s, parsed = call("POST", "/api/runbooks/parse", sam, {"content": "<<odd format>> step one; step two"})
check("a runbook in an unknown format is still accepted as text", s == 200 and parsed["format"] == "text" and len(parsed["steps"]) == 1)
lat = {"service": "pricing-engine-2", "level": "WARN", "instrument": "SMK" + RUN, "latencyMs": 1240, "message": "Pricing latency above 1s cache", "time": "2026-10-23T09:15:00Z"}
call("POST", "/ingest/pricing-logs", body=lat, headers={"X-Source-Token": "demo-pricing-logs"})
pa = wait_for(lambda: alerts(petra, f"instrument = 'SMK{RUN}'"))
s, rb = call("GET", f"/api/alerts/{pa[0]['alertId']}/runbook", petra) if pa else (0, None)
check("an unlinked free-text runbook is suggested by search", s == 200 and (rb["runbook"] or rb["suggestions"]) and (rb["runbook"] or rb["suggestions"][0]["runbookId"] == "pricing-latency"), str(rb)[:300])

print(f"\nAll {passed} checks passed.")
