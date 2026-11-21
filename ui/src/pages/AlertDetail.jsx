import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../api.js';
import { useAuth } from '../auth.jsx';
import { toast } from '../toast.js';
import { useAlertStream } from '../useAlertStream.js';
import { alertColor, asArray, fmtTime, pick, show } from '../util.js';
import { Empty, ErrorBox, Field, Help, JsonBlock, Section, SeverityBadge, StatusBadge, TemplateText } from '../components/ui.jsx';

const isPendingApproval = (status) => /pending|await|approval/i.test(String(status || ''));
const isDone = (status) => /^(done|success|succeeded|completed|executed|ok)$/i.test(String(status || ''));

function stepStatusLabel(status) {
  if (!status) return 'Not run';
  if (isPendingApproval(status)) return 'Waiting for approval';
  return String(status).replace(/_/g, ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase());
}

function describeAction(action) {
  if (!action) return null;
  if (typeof action === 'string') return action;
  const params = action.params && Object.keys(action.params).length ? ` ${JSON.stringify(action.params)}` : '';
  return `${action.type || 'action'}${params}`;
}

function RunbookPanel({ alert, data, error, reload }) {
  const { isAdminOf } = useAuth();
  const [busy, setBusy] = useState(null);
  const id = encodeURIComponent(alert.alertId);
  const canAdmin = isAdminOf(alert.teamId);

  const call = async (key, path, body, okMsg) => {
    setBusy(key);
    try {
      await api.post(path, body || {});
      toast.success(okMsg);
    } catch (e) {
      toast.error(e);
    } finally {
      setBusy(null);
      reload();
    }
  };

  if (error) return <ErrorBox>{error}</ErrorBox>;
  if (!data) return <Empty>Loading runbook…</Empty>;

  const runbook = data.runbook;
  const steps = asArray(data.steps);
  const suggestions = asArray(data.suggestions);

  if (!runbook && steps.length === 0) {
    return (
      <div>
        <Empty title="No runbook is attached to this alert">
          {suggestions.length ? 'These runbooks look related. Pick one to attach it to the rule.' : 'No similar runbooks were found. You can add one on the Runbooks page.'}
        </Empty>
        {suggestions.length > 0 && (
          <table className="table">
            <thead>
              <tr>
                <th>
                  Suggested runbook
                  <Help text="No runbook is linked to this alert's rule, and none of the team's runbooks has a 'matches' section that fits it. These suggestions come from a text search of the team's runbooks for words in this alert (its title, type, service, message and event fields)." />
                </th>
                <th>
                  Match
                  <Help text="How well the runbook's words match this alert, compared with the other suggestions; the best one is lower than 100% when even it shares only a few words. It is a hint, not a guarantee: read the steps before using it." />
                </th>
                <th />
              </tr>
            </thead>
            <tbody>
              {suggestions.map((s) => (
                <tr key={s.runbookId}>
                  <td>{s.name || s.runbookId}</td>
                  <td>{typeof s.score === 'number' ? `${Math.round(s.score * 100)}%` : show(s.score) || '—'}</td>
                  <td className="right">
                    <button
                      type="button"
                      className="btn small primary"
                      disabled={busy === `link-${s.runbookId}`}
                      title={canAdmin ? '' : 'Only a team admin can attach a runbook'}
                      onClick={() => call(`link-${s.runbookId}`, `/api/alerts/${id}/runbook/link`, { runbookId: s.runbookId }, 'Runbook attached to the rule')}
                    >
                      Use this runbook
                    </button>
                    <Help text="Links the runbook to this alert's rule. From then on every alert of the rule shows it directly, without searching." />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    );
  }

  return (
    <div>
      {runbook && (
        <p className="muted">
          Runbook: <strong>{runbook.name || runbook.id}</strong>
          {runbook.format ? ` (${runbook.format})` : ''}
        </p>
      )}
      {steps.length === 0 && <Empty>This runbook has no steps.</Empty>}
      <ol className="steps">
        {steps.map((s, i) => {
          const index = s.index != null ? s.index : i;
          const pending = isPendingApproval(s.status);
          const unresolved = asArray(s.unresolved);
          const needsApproval = String(s.approval || '').toLowerCase() === 'required';
          const key = `step-${index}`;
          return (
            <li key={index} className={`step ${pending ? 'pending' : ''}`}>
              <div className="step-text"><TemplateText text={s.text || '(no description)'} /></div>
              {s.condition && <div className="step-line"><span className="tag">When</span> <TemplateText text={s.condition} /></div>}
              {s.action && (
                <div className="step-line">
                  <span className="tag">Action</span> <code><TemplateText text={describeAction(s.action)} /></code>
                  {needsApproval && <span className="badge warn">needs approval</span>}
                </div>
              )}
              {unresolved.length > 0 && (
                <div className="step-line">
                  <span className="tag">Missing values</span>{' '}
                  {unresolved.map((u) => <mark className="missing" key={show(u)}>{show(u)}</mark>)}
                </div>
              )}
              {(s.action || s.status) && (
                <div className="step-line">
                  <span className="tag">Status</span> <span className={`badge step-status ${pending ? 'warn' : ''}`}>{stepStatusLabel(s.status)}</span>
                </div>
              )}
              {s.result != null && s.result !== '' && (
                <div className="step-line"><span className="tag">Result</span> <code className="wrap"><TemplateText text={s.result} /></code></div>
              )}
              <div className="row">
                {s.action && !pending && (
                  <button
                    type="button"
                    className="btn small primary"
                    disabled={busy === key}
                    onClick={() => call(key, `/api/alerts/${id}/runbook/steps/${index}/execute`, {}, needsApproval ? 'Approval requested' : 'Step executed')}
                  >
                    {busy === key ? 'Running…' : isDone(s.status) ? 'Run again' : needsApproval ? 'Run (asks for approval)' : 'Run'}
                  </button>
                )}
                {pending && (
                  <>
                    <button type="button" className="btn small primary" disabled={busy === key} title={canAdmin ? '' : 'Only a team admin can approve'}
                      onClick={() => call(key, `/api/alerts/${id}/runbook/steps/${index}/approve`, {}, 'Step approved')}>
                      Approve
                    </button>
                    <button type="button" className="btn small danger" disabled={busy === key} title={canAdmin ? '' : 'Only a team admin can reject'}
                      onClick={() => call(key, `/api/alerts/${id}/runbook/steps/${index}/reject`, {}, 'Step rejected')}>
                      Reject
                    </button>
                    {!canAdmin && <span className="hint">Waiting for a team admin.</span>}
                  </>
                )}
              </div>
            </li>
          );
        })}
      </ol>
    </div>
  );
}

export default function AlertDetail() {
  const { id } = useParams();
  const { teamName, userName, isAdminOf } = useAuth();
  const navigate = useNavigate();
  const [drafting, setDrafting] = useState(false);
  const draftRunbook = async () => {
    setDrafting(true);
    try {
      const draft = await api.get(`/api/alerts/${encodeURIComponent(id)}/runbook-draft`);
      navigate('/runbooks', { state: { runbookDraft: draft } });
    } catch (e) {
      toast.error(e);
      setDrafting(false);
    }
  };
  const [alert, setAlert] = useState(null);
  const [error, setError] = useState(null);
  const [runbook, setRunbook] = useState(null);
  const [runbookError, setRunbookError] = useState(null);
  const [deliveries, setDeliveries] = useState(null);
  const [audit, setAudit] = useState(null);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const base = `/api/alerts/${encodeURIComponent(id)}`;

  const loadAlert = useCallback(() => {
    api.get(base).then((a) => { setAlert(a); setError(null); }).catch((e) => setError(e.message));
  }, [base]);

  const loadRunbook = useCallback(() => {
    api.get(`${base}/runbook`).then((r) => { setRunbook(r || {}); setRunbookError(null); }).catch((e) => setRunbookError(e.message));
  }, [base]);

  const loadSide = useCallback(() => {
    api.get(`${base}/deliveries`).then((d) => setDeliveries(asArray(d))).catch(() => setDeliveries([]));
    api.get(`${base}/audit`).then((d) => setAudit(asArray(d))).catch(() => setAudit([]));
  }, [base]);

  useEffect(() => {
    loadAlert();
    loadRunbook();
    loadSide();
  }, [loadAlert, loadRunbook, loadSide]);

  const sideTimer = useRef(null);
  useEffect(() => () => clearTimeout(sideTimer.current), []);
  const refreshSideSoon = useCallback(() => {
    clearTimeout(sideTimer.current);
    sideTimer.current = setTimeout(loadSide, 1500);
  }, [loadSide]);

  useAlertStream('', (a) => {
    if (a.alertId === id) {
      setAlert(a);
      refreshSideSoon();
    }
  });

  const act = async (action) => {
    setBusy(true);
    try {
      await api.post(`${base}/${action}`, note.trim() ? { note: note.trim() } : {});
      toast.success(action === 'ack' ? 'Acknowledged' : 'Resolved');
      setNote('');
      setTimeout(() => { loadAlert(); loadSide(); }, 1200);
    } catch (e) {
      toast.error(e);
    } finally {
      setBusy(false);
    }
  };

  if (error && !alert) {
    return (
      <div>
        <p><Link to="/">← Back to alerts</Link></p>
        <ErrorBox>{error}</ErrorBox>
      </div>
    );
  }
  if (!alert) return <Empty>Loading alert…</Empty>;

  const fields = alert.fields && typeof alert.fields === 'object' ? Object.entries(alert.fields) : [];
  const groupValues = alert.groupValues && typeof alert.groupValues === 'object' ? Object.entries(alert.groupValues) : [];

  return (
    <div>
      <p><Link to="/">← Back to alerts</Link></p>

      <div className="card detail-head" style={{ borderLeftColor: alertColor(alert) }}>
        <div className="detail-title">
          <h1><TemplateText text={alert.title || alert.type || alert.alertId} /></h1>
          <div className="counter" title="Number of matching events grouped into this alert">
            <div className="counter-num">×{alert.count != null ? alert.count : 1}</div>
            <div className="muted">events</div>
          </div>
        </div>
        <div className="alert-meta">
          <SeverityBadge severity={alert.severity} />
          <StatusBadge status={alert.status} />
          <span>Team: <strong>{teamName(alert.teamId) || '—'}</strong></span>
          <span>Rule: <strong>{alert.ruleName || alert.ruleId || '—'}</strong></span>
          {alert.sourceId && <span>Source: <strong>{alert.sourceId}</strong></span>}
          {alert.service && <span>Service: <strong>{alert.service}</strong></span>}
          <span>First seen {fmtTime(alert.firstSeen)}</span>
          <span>Last seen {fmtTime(alert.lastSeen)}</span>
        </div>
        {alert.body && <p className="alert-body"><TemplateText text={alert.body} /></p>}
        {(alert.ackBy || alert.resolvedBy || alert.note) && (
          <div className="muted">
            {alert.ackBy && <div>Acknowledged by {userName(alert.ackBy)} at {fmtTime(alert.ackAt)}</div>}
            {alert.resolvedBy && (
              <div>
                Resolved by {userName(alert.resolvedBy)} at {fmtTime(alert.resolvedAt)}
                {alert.resolution ? ` (${alert.resolution})` : ''}
              </div>
            )}
            {alert.note && <div>Note: {alert.note}</div>}
          </div>
        )}

        {alert.status !== 'RESOLVED' ? (
          <div className="row wrap status-actions">
            <Field label="Note (optional)" className="grow">
              <input type="text" value={note} onChange={(e) => setNote(e.target.value)} placeholder="e.g. Restarted the counterparty service" />
            </Field>
            {alert.status === 'OPEN' && <button type="button" className="btn align-end" disabled={busy} onClick={() => act('ack')}>Acknowledge</button>}
            <button type="button" className="btn primary align-end" disabled={busy} onClick={() => act('resolve')}>Resolve</button>
          </div>
        ) : (
          <div className="row wrap status-actions">
            <div className="hint grow">This alert is resolved. A new matching event opens a new alert.</div>
            {isAdminOf(alert.teamId) && (
              <button type="button" className="btn" disabled={drafting} onClick={draftRunbook}>
                {drafting ? 'Writing draft…' : 'Save what was done as a runbook'}
              </button>
            )}
          </div>
        )}
      </div>

      <div className="grid2">
        <div>
          <Section title="Fields">
            {fields.length === 0 && groupValues.length === 0 ? (
              <Empty>The rule does not list any fields to show.</Empty>
            ) : (
              <table className="table kv">
                <tbody>
                  {fields.map(([k, v]) => <tr key={`f-${k}`}><th>{k}</th><td><TemplateText text={show(v)} /></td></tr>)}
                  {groupValues.filter(([k]) => !(alert.fields && k in alert.fields)).map(([k, v]) => (
                    <tr key={`g-${k}`}><th>{k} <span className="muted">(grouped by)</span></th><td>{show(v)}</td></tr>
                  ))}
                </tbody>
              </table>
            )}
            <details className="raw">
              <summary>Raw last event (JSON)</summary>
              {alert.lastEvent ? <JsonBlock value={alert.lastEvent} /> : <Empty>No event recorded.</Empty>}
            </details>
          </Section>

          <Section title="Notifications sent" hint="Who was told about this alert, and how.">
            {deliveries === null ? <Empty>Loading…</Empty> : deliveries.length === 0 ? (
              <Empty>No notifications have been sent for this alert yet.</Empty>
            ) : (
              <div className="table-wrap">
                <table className="table">
                  <thead><tr><th>Recipient</th><th>Channel</th><th>Address</th><th>Status</th><th>Attempt</th><th>Latency</th><th>Updated</th></tr></thead>
                  <tbody>
                    {deliveries.map((d, i) => {
                      const st = String(d.status || '');
                      const latency = pick(d, 'latencyMs', 'latency_ms');
                      return (
                        <tr key={pick(d, 'notificationId', 'notification_id') || i}>
                          <td>{userName(pick(d, 'userId', 'user_id')) || '—'}</td>
                          <td>{d.channel || '—'}</td>
                          <td>{d.address || '—'}</td>
                          <td>
                            <span className={`badge delivery-${st.toLowerCase()}`}>{st || '—'}</span>
                            {d.error && <div className="error-text">{show(d.error)}</div>}
                          </td>
                          <td>{d.attempt != null ? d.attempt : '—'}</td>
                          <td>{latency != null ? `${latency} ms` : '—'}</td>
                          <td>{fmtTime(pick(d, 'updatedAt', 'updated_at', 'createdAt', 'created_at'))}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </Section>
        </div>

        <div>
          <Section title="Runbook" hint="What to do about this alert. Steps are filled in with values from this alert. A step can be text to follow or an action the service runs for you (a health check, a retry, a restart, an escalation); risky actions wait for a team admin to approve. Everything done here is recorded in the history below.">
            <RunbookPanel alert={alert} data={runbook} error={runbookError} reload={() => { loadRunbook(); refreshSideSoon(); }} />
          </Section>

          <Section title="History" hint="Everything that happened to this alert.">
            {audit === null ? <Empty>Loading…</Empty> : audit.length === 0 ? (
              <Empty>No history yet.</Empty>
            ) : (
              <ul className="audit">
                {audit.map((r, i) => {
                  const detail = r.detail;
                  const hasDetail = detail && (typeof detail !== 'object' || Object.keys(detail).length > 0);
                  return (
                    <li key={r.id || i}>
                      <div>
                        <strong>{r.action || 'event'}</strong> by {userName(pick(r, 'userId', 'user_id')) || 'system'}
                        <span className="muted"> · {fmtTime(pick(r, 'ts', 'timestamp', 'createdAt', 'created_at'))}</span>
                      </div>
                      {hasDetail && <div className="muted wrap">{show(detail)}</div>}
                    </li>
                  );
                })}
              </ul>
            )}
          </Section>
        </div>
      </div>
    </div>
  );
}
