import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, qs } from '../api.js';
import { useAuth } from '../auth.jsx';
import { toast } from '../toast.js';
import { useAlertStream } from '../useAlertStream.js';
import { alertColor, asArray, fmtAgo, fmtTime, sortAlerts } from '../util.js';
import FilterEditor, { emptyFilter, filterFromSql, resolveFilter } from '../components/FilterEditor.jsx';
import { Empty, ErrorBox, Field, Modal, SeverityBadge, StatusBadge } from '../components/ui.jsx';

const MAX_ROWS = 300;

export default function Feed() {
  const { teams, teamName, me } = useAuth();
  const [alerts, setAlerts] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [teamId, setTeamId] = useState('');
  const [status, setStatus] = useState('');
  const [filter, setFilter] = useState(emptyFilter());
  const [appliedSql, setAppliedSql] = useState('');
  const [fields, setFields] = useState([]);
  const [saved, setSaved] = useState([]);
  const [savedId, setSavedId] = useState('');
  const [saveForm, setSaveForm] = useState(null);
  const [applying, setApplying] = useState(false);
  const [, tick] = useState(0);

  const load = useCallback(async () => {
    try {
      const list = await api.get(`/api/alerts${qs({ teamId, status, filter: appliedSql, limit: 100 })}`);
      setAlerts(sortAlerts(asArray(list)));
      setError(null);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [teamId, status, appliedSql]);

  useEffect(() => {
    load();
  }, [load]);

  const loadSaved = useCallback(() => {
    api.get('/api/saved-filters').then((s) => setSaved(asArray(s))).catch(() => setSaved([]));
  }, []);

  useEffect(() => {
    api.get('/api/fields').then((f) => setFields(asArray(f))).catch(() => setFields([]));
    loadSaved();
    const t = setInterval(() => tick((n) => n + 1), 30000); // refresh "x ago" labels
    return () => clearInterval(t);
  }, [loadSaved]);

  // Live updates. Latest state is read through a ref so the stream is not re-opened on every change.
  const live = useRef({});
  live.current = { teamId, status, appliedSql, alerts, load };
  const refetchTimer = useRef(null);
  useEffect(() => () => clearTimeout(refetchTimer.current), []);

  const onAlert = useCallback((a) => {
    if (!a.alertId) return;
    const s = live.current;
    const selected = (!s.teamId || a.teamId === s.teamId) && (!s.status || a.status === s.status);
    const known = s.alerts.some((x) => x.alertId === a.alertId);
    if (known) {
      setAlerts((prev) =>
        selected ? sortAlerts(prev.map((x) => (x.alertId === a.alertId ? a : x))) : prev.filter((x) => x.alertId !== a.alertId)
      );
    } else if (selected) {
      if (s.appliedSql) {
        // Only the server can tell whether a new alert matches the active filter: refetch, debounced.
        clearTimeout(refetchTimer.current);
        refetchTimer.current = setTimeout(() => live.current.load(), 600);
      } else {
        setAlerts((prev) =>
          prev.some((x) => x.alertId === a.alertId)
            ? sortAlerts(prev.map((x) => (x.alertId === a.alertId ? a : x)))
            : sortAlerts([a, ...prev]).slice(0, MAX_ROWS)
        );
      }
    }
  }, []);
  const connected = useAlertStream(teamId, onAlert);

  const apply = async (value = filter) => {
    setApplying(true);
    try {
      const { sql } = await resolveFilter(value, fields, { needSql: true });
      if (sql === appliedSql) load();
      else {
        setLoading(true);
        setAppliedSql(sql);
      }
    } catch (e) {
      toast.error(e);
    } finally {
      setApplying(false);
    }
  };

  const clear = () => {
    setFilter(emptyFilter());
    setSavedId('');
    setAppliedSql('');
  };

  const pickSaved = async (id) => {
    setSavedId(id);
    const f = saved.find((x) => String(x.id) === String(id));
    if (!f) return;
    const value = await filterFromSql(f.sql || '');
    setFilter(value);
    setLoading(true);
    setAppliedSql((f.sql || '').trim());
  };

  const openSave = async () => {
    try {
      const { sql, empty } = await resolveFilter(filter, fields, { needSql: true });
      if (empty) return toast.info('Add at least one condition before saving a filter.');
      setSaveForm({ name: '', scope: 'personal', teamId: teamId || (me.teams[0] && me.teams[0].teamId) || (teams[0] && teams[0].id) || '', sql });
    } catch (e) {
      toast.error(e);
    }
  };

  const save = async () => {
    if (!saveForm.name.trim()) return toast.error('Give the filter a name.');
    if (saveForm.scope === 'team' && !saveForm.teamId) return toast.error('Choose a team to share the filter with.');
    try {
      const body = { name: saveForm.name.trim(), scope: saveForm.scope, sql: saveForm.sql };
      if (saveForm.scope === 'team') body.teamId = saveForm.teamId;
      const created = await api.post('/api/saved-filters', body);
      toast.success('Filter saved');
      setSaveForm(null);
      loadSaved();
      if (created && created.id) setSavedId(String(created.id));
    } catch (e) {
      toast.error(e);
    }
  };

  const deleteSaved = async () => {
    const f = saved.find((x) => String(x.id) === String(savedId));
    if (!f || !window.confirm(`Delete the saved filter "${f.name}"?`)) return;
    try {
      await api.del(`/api/saved-filters/${encodeURIComponent(f.id)}`);
      toast.success('Saved filter deleted');
      setSavedId('');
      loadSaved();
    } catch (e) {
      toast.error(e);
    }
  };

  const act = async (a, action) => {
    try {
      await api.post(`/api/alerts/${encodeURIComponent(a.alertId)}/${action}`, {});
      toast.success(action === 'ack' ? 'Acknowledged' : 'Resolved');
      setTimeout(() => live.current.load(), 1200); // fallback in case the live update is missed
    } catch (e) {
      toast.error(e);
    }
  };

  return (
    <div>
      <div className="page-head">
        <h1>Alerts</h1>
        <span className={`live ${connected ? 'on' : 'off'}`} title={connected ? 'Receiving live updates' : 'Live updates are disconnected; retrying'}>
          <span className="live-dot" /> {connected ? 'Live' : 'Reconnecting…'}
        </span>
      </div>

      <div className="card">
        <div className="row wrap">
          <Field label="Team">
            <select value={teamId} onChange={(e) => { setLoading(true); setTeamId(e.target.value); }}>
              <option value="">All my teams</option>
              {teams.map((t) => <option key={t.id} value={t.id}>{t.name || t.id}</option>)}
            </select>
          </Field>
          <Field label="Status">
            <select value={status} onChange={(e) => { setLoading(true); setStatus(e.target.value); }}>
              <option value="">Any status</option>
              <option value="OPEN">Open</option>
              <option value="ACKNOWLEDGED">Acknowledged</option>
              <option value="RESOLVED">Resolved</option>
            </select>
          </Field>
          <Field label="Saved filters">
            <select value={savedId} onChange={(e) => pickSaved(e.target.value)}>
              <option value="">{saved.length ? 'Choose a saved filter…' : 'No saved filters yet'}</option>
              {saved.map((f) => (
                <option key={f.id} value={f.id}>
                  {f.name} ({f.scope === 'team' ? `team${f.teamId ? `: ${teamName(f.teamId)}` : ''}` : 'personal'})
                </option>
              ))}
            </select>
          </Field>
          {savedId && <button type="button" className="btn small ghost align-end" onClick={deleteSaved}>Delete saved filter</button>}
        </div>

        <div className="filter-panel">
          <div className="field-label">Filter</div>
          <FilterEditor value={filter} onChange={setFilter} fields={fields} emptyText="No conditions. All alerts are shown. Add a condition to narrow the list." />
          <div className="row">
            <button type="button" className="btn primary" onClick={() => apply()} disabled={applying}>{applying ? 'Applying…' : 'Apply filter'}</button>
            <button type="button" className="btn" onClick={clear}>Clear</button>
            <button type="button" className="btn" onClick={openSave}>Save current filter</button>
            {appliedSql && <span className="muted applied">Active filter: <code>{appliedSql}</code></span>}
          </div>
        </div>
      </div>

      <ErrorBox>{error}</ErrorBox>

      {loading && alerts.length === 0 ? (
        <Empty>Loading alerts…</Empty>
      ) : alerts.length === 0 ? (
        <Empty title="No alerts to show">
          {appliedSql || status || teamId
            ? 'Nothing matches the current team, status and filter. Try clearing the filter.'
            : 'When a rule matches an incoming event, the alert appears here straight away.'}
        </Empty>
      ) : (
        <div className="alert-list">
          {alerts.map((a) => (
            <div className={`alert-row status-${String(a.status || '').toLowerCase()}`} key={a.alertId}>
              <div className="sevbar" style={{ background: alertColor(a) }} />
              <div className="alert-main">
                <div className="alert-title">
                  <Link to={`/alerts/${encodeURIComponent(a.alertId)}`}>{a.title || a.type || a.alertId}</Link>
                  {a.count > 1 && <span className="badge count" title={`${a.count} matching events`}>×{a.count}</span>}
                </div>
                <div className="alert-meta">
                  <SeverityBadge severity={a.severity} />
                  <StatusBadge status={a.status} />
                  <span>Team: <strong>{teamName(a.teamId) || '—'}</strong></span>
                  <span>Rule: <strong>{a.ruleName || a.ruleId || '—'}</strong></span>
                  <span title={fmtTime(a.firstSeen)}>First seen {fmtAgo(a.firstSeen)}</span>
                  <span title={fmtTime(a.lastSeen)}>Last seen {fmtAgo(a.lastSeen)}</span>
                </div>
              </div>
              <div className="alert-actions">
                {a.status === 'OPEN' && <button type="button" className="btn small" onClick={() => act(a, 'ack')}>Ack</button>}
                {a.status !== 'RESOLVED' && <button type="button" className="btn small" onClick={() => act(a, 'resolve')}>Resolve</button>}
              </div>
            </div>
          ))}
        </div>
      )}

      {saveForm && (
        <Modal
          title="Save current filter"
          onClose={() => setSaveForm(null)}
          actions={
            <>
              <button type="button" className="btn" onClick={() => setSaveForm(null)}>Cancel</button>
              <button type="button" className="btn primary" onClick={save}>Save filter</button>
            </>
          }
        >
          <Field label="Name">
            <input type="text" autoFocus value={saveForm.name} onChange={(e) => setSaveForm({ ...saveForm, name: e.target.value })} placeholder="e.g. Critical in prod" />
          </Field>
          <Field label="Who can use it">
            <select value={saveForm.scope} onChange={(e) => setSaveForm({ ...saveForm, scope: e.target.value })}>
              <option value="personal">Only me (personal)</option>
              <option value="team">My team</option>
            </select>
          </Field>
          {saveForm.scope === 'team' && (
            <Field label="Team">
              <select value={saveForm.teamId} onChange={(e) => setSaveForm({ ...saveForm, teamId: e.target.value })}>
                <option value="">Choose a team…</option>
                {teams.map((t) => <option key={t.id} value={t.id}>{t.name || t.id}</option>)}
              </select>
            </Field>
          )}
          <div className="hint">Filter: <code>{saveForm.sql}</code></div>
        </Modal>
      )}
    </div>
  );
}
