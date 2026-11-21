import React, { useCallback, useEffect, useState } from 'react';
import { api } from '../api.js';
import { useAuth } from '../auth.jsx';
import { toast } from '../toast.js';
import { SAMPLES, sampleText } from '../samples.js';
import { asArray, flattenPaths, fmtTime, show } from '../util.js';
import { FORMATS, FORMAT_LABELS, describeParsed, parseSampleText } from '../sampleParse.js';
import FilterEditor, { emptyFilter, resolveFilter } from '../components/FilterEditor.jsx';
import { Combobox, CopyButton, Empty, ErrorBox, Field, Help, Section, useConfirm } from '../components/ui.jsx';
import { useLeaveGuard, useUnsavedChanges } from '../unsaved.jsx';

const sampleFor = (source) => {
  const byschema = SAMPLES.find((s) => s.id === source.defaultSchemaId);
  return (byschema || SAMPLES[0]).event;
};

function curlFor(source, token) {
  const url = `${window.location.origin}${source.ingestUrl || `/ingest/${source.id}`}`;
  const body = JSON.stringify(sampleFor(source)).replace(/'/g, "'\\''");
  return [
    `curl -X POST '${url}' \\`,
    `  -H 'Content-Type: application/json' \\`,
    `  -H 'X-Source-Token: ${token || '<token>'}' \\`,
    `  -d '${body}'`,
  ].join('\n');
}

function SourceCard({ source, schemas, reload }) {
  const { teams, teamName, isAdminOf } = useAuth();
  const canAdmin = isAdminOf(source.teamId);
  const [showToken, setShowToken] = useState(false);
  const [grantTeam, setGrantTeam] = useState('');
  const [busy, setBusy] = useState(false);
  const grants = asArray(source.grantedTeamIds);
  const id = encodeURIComponent(source.id);
  const schema = schemas.find((s) => s.id === source.defaultSchemaId);
  const curl = curlFor(source, source.token);
  const [confirm, confirmDialog] = useConfirm();

  const run = async (fn, okMsg) => {
    setBusy(true);
    try {
      await fn();
      if (okMsg) toast.success(okMsg);
      reload();
    } catch (e) {
      toast.error(e);
    } finally {
      setBusy(false);
    }
  };

  const giveAccess = async () => {
    const t = grantTeam.trim();
    if (!t) return true;
    setBusy(true);
    try {
      await api.post(`/api/sources/${id}/grants`, { teamId: t });
      setGrantTeam('');
      toast.success('Access granted');
      reload();
      return true;
    } catch (e) {
      toast.error(e);
      return false;
    } finally {
      setBusy(false);
    }
  };
  useUnsavedChanges(grantTeam.trim() !== '', giveAccess);

  const remove = async () => {
    const ok = await confirm({
      title: `Delete the source "${source.name || source.id}"?`,
      body: 'Rules that read it will stop receiving its events. This cannot be undone.',
      cancelLabel: 'Keep source',
      confirmLabel: 'Delete source',
    });
    if (ok) run(() => api.del(`/api/sources/${id}`), 'Source deleted');
  };

  const sendTest = async () => {
    setBusy(true);
    try {
      const r = await api.post(source.ingestUrl || `/ingest/${id}`, sampleFor(source), { 'X-Source-Token': source.token || '' });
      toast.success(`Test event accepted${r && r.accepted != null ? ` (${r.accepted})` : ''}. Check the Alerts page.`);
    } catch (e) {
      toast.error(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="item">
      <div className="item-head">
        <div>
          <strong>{source.name || source.id}</strong> <span className="badge">{source.type || 'source'}</span>
          <div className="muted">
            ID <code>{source.id}</code> · Team {teamName(source.teamId) || '—'} · Default schema {schema ? schema.name : source.defaultSchemaId || 'none (auto-detect)'} · Format {FORMAT_LABELS[source.format || 'auto'] || source.format}
          </div>
        </div>
        {canAdmin && (
          <button type="button" className="btn small danger" disabled={busy} onClick={remove}>
            Delete
          </button>
        )}
      </div>

      {source.type === 'poll' && <PollInfo source={source} />}

      <table className="table kv compact">
        <tbody>
          <tr>
            <th>Webhook URL{source.type === 'poll' && <Help text="A polling source can also receive pushed events here, like any webhook source." />}</th>
            <td><code>{source.ingestUrl || `/ingest/${source.id}`}</code> <CopyButton text={`${window.location.origin}${source.ingestUrl || `/ingest/${source.id}`}`} /></td>
          </tr>
          <tr>
            <th>Kafka topic</th>
            <td><code>{source.topic || `ingest.${source.id}`}</code> <CopyButton text={source.topic || `ingest.${source.id}`} /></td>
          </tr>
          <tr>
            <th>Token</th>
            <td>
              {source.token ? (
                <>
                  <code>{showToken ? source.token : '••••••••••••'}</code>{' '}
                  <button type="button" className="btn small ghost" onClick={() => setShowToken(!showToken)}>{showToken ? 'Hide' : 'Show'}</button>{' '}
                  <CopyButton text={source.token} />
                </>
              ) : (
                <span className="muted">Hidden (only the owning team can see it)</span>
              )}
            </td>
          </tr>
        </tbody>
      </table>

      <details>
        <summary>Send an event with curl</summary>
        <pre className="code">{curl}</pre>
        <div className="row">
          <CopyButton text={curl} label="Copy command" />
          {source.token && <button type="button" className="btn small" disabled={busy} onClick={sendTest}>Send this test event now</button>}
        </div>
      </details>

      <div className="grants">
        <span className="field-label">Teams allowed to use this source in their rules</span>
        <div className="chips">
          <span className="chip owner">{teamName(source.teamId) || source.teamId} (owner)</span>
          {grants.map((g) => (
            <span className="chip" key={g}>
              {teamName(g)}
              {canAdmin && (
                <button type="button" aria-label={`Remove access for ${teamName(g)}`} disabled={busy}
                  onClick={() => run(() => api.del(`/api/sources/${id}/grants/${encodeURIComponent(g)}`), 'Access removed')}>×</button>
              )}
            </span>
          ))}
          {grants.length === 0 && <span className="muted">No other team has access.</span>}
        </div>
        {canAdmin && (
          <div className="row">
            <Combobox
              value={grantTeam}
              onChange={setGrantTeam}
              options={teams.filter((t) => t.id !== source.teamId && !grants.includes(t.id)).map((t) => ({ value: t.id, detail: t.name }))}
              placeholder="Team ID, e.g. rates"
              aria-label="Team to give access"
            />
            <button type="button" className="btn small" disabled={busy || !grantTeam.trim()} onClick={giveAccess}>
              Give access
            </button>
          </div>
        )}
      </div>
      {confirmDialog}
    </div>
  );
}

/** Polling sources: what is fetched, how often, and how the last fetch went. */
function PollInfo({ source }) {
  const p = source.poll || {};
  const st = source.pollStatus;
  return (
    <div className="poll-info">
      <div>
        Fetches <code>{p.url}</code> every {p.intervalSeconds || 30} s
        {p.onlyWhenChanged !== false ? ', and sends it on only when the response has changed' : ', and sends every response'}.
        <Help text="When the URL stops answering (or answers with an error), one POLL_FAILED event is sent, and POLL_RECOVERED when it answers again. Rules can alert on type = 'POLL_FAILED'." />
      </div>
      {st ? (
        <div className={st.ok ? 'muted' : 'error-text'}>
          Last fetch {fmtTime(st.lastPollAt)}: {st.ok ? `OK (HTTP ${st.httpStatus}, ${st.tookMs} ms)` : `failed: ${st.error}`} · {st.eventsSent || 0} event(s) sent
          {st.lastSentAt ? `, last at ${fmtTime(st.lastSentAt)}` : ''}
        </div>
      ) : (
        <div className="muted">Not fetched yet; the first fetch happens within a few seconds.</div>
      )}
    </div>
  );
}

const EMPTY_POLL = { url: '', intervalSeconds: 30, onlyWhenChanged: true };
const EMPTY_SOURCE = { name: '', teamId: '', type: 'webhook', defaultSchemaId: '', format: 'auto', poll: EMPTY_POLL };

function CreateSource({ schemas, onDone, onCancel }) {
  const { adminTeams } = useAuth();
  const [form, setForm] = useState(EMPTY_SOURCE);
  const [busy, setBusy] = useState(false);
  const teamId = form.teamId || (adminTeams[0] && adminTeams[0].id) || '';
  const set = (patch) => setForm({ ...form, ...patch });

  const create = async () => {
    if (!form.name.trim()) { toast.error('Give the source a name.'); return false; }
    setBusy(true);
    try {
      const body = { name: form.name.trim(), teamId, type: form.type, defaultSchemaId: form.defaultSchemaId || null, format: form.format };
      if (form.type === 'poll') {
        if (!form.poll.url.trim()) { toast.error('Give the URL to fetch.'); return false; }
        body.poll = { url: form.poll.url.trim(), intervalSeconds: Math.max(5, Number(form.poll.intervalSeconds) || 30), onlyWhenChanged: form.poll.onlyWhenChanged };
      }
      await api.post('/api/sources', body);
      toast.success('Source created');
      setForm(EMPTY_SOURCE);
      onDone();
      return true;
    } catch (err) {
      toast.error(err);
      return false;
    } finally {
      setBusy(false);
    }
  };
  const guard = useUnsavedChanges(JSON.stringify(form) !== JSON.stringify(EMPTY_SOURCE), create);

  const submit = (e) => {
    e.preventDefault();
    create();
  };

  return (
    <form onSubmit={submit} className="row wrap">
      <Field label="Name"><input type="text" value={form.name} onChange={(e) => set({ name: e.target.value })} placeholder="e.g. Pricing logs" /></Field>
      <Field label="Owning team">
        <select value={teamId} onChange={(e) => set({ teamId: e.target.value })}>
          {adminTeams.map((t) => <option key={t.id} value={t.id}>{t.name || t.id}</option>)}
        </select>
      </Field>
      <Field label="How events arrive">
        <select value={form.type} onChange={(e) => set({ type: e.target.value })}>
          <option value="webhook">Webhook (they send us HTTP POSTs)</option>
          <option value="kafka">Kafka topic (they write to a topic)</option>
          <option value="poll">HTTP polling (we fetch a URL on a schedule)</option>
        </select>
      </Field>
      {form.type === 'poll' && (
        <>
          <Field label="URL to fetch" className="grow" hint="For example a status page or a health or metrics endpoint. Its host must be on the allowlist a platform admin keeps. The response can be in any format.">
            <input type="text" value={form.poll.url} onChange={(e) => set({ poll: { ...form.poll, url: e.target.value } })} placeholder="http://localhost:8080/mock/status/my-service" />
          </Field>
          <Field label="Every (seconds)">
            <input type="number" min={5} value={form.poll.intervalSeconds} onChange={(e) => set({ poll: { ...form.poll, intervalSeconds: e.target.value } })} style={{ width: 90 }} />
          </Field>
          <label className="check align-end">
            <input type="checkbox" checked={form.poll.onlyWhenChanged} onChange={(e) => set({ poll: { ...form.poll, onlyWhenChanged: e.target.checked } })} />
            Only when the response changes
            <Help text="Off: every fetch becomes an event (useful when each response is a new reading). On: an unchanged status page sends nothing." />
          </label>
        </>
      )}
      <Field label="Message format" hint="Leave on automatic unless every message has the same format. Detection handles JSON, JSON lines, CSV/TSV with a header row, YAML, XML, key=value pairs, syslog, form fields and plain text (one event per line).">
        <select value={form.format} onChange={(e) => set({ format: e.target.value })}>
          {FORMATS.map(([k, label]) => <option key={k} value={k}>{label}</option>)}
        </select>
      </Field>
      <Field label="Default schema">
        <select value={form.defaultSchemaId} onChange={(e) => set({ defaultSchemaId: e.target.value })}>
          <option value="">None (auto-detect)</option>
          {schemas.map((s) => <option key={s.id} value={s.id}>{s.name || s.id}</option>)}
        </select>
      </Field>
      <button type="submit" className="btn primary align-end" disabled={busy}>Create source</button>
      <button type="button" className="btn align-end" onClick={() => guard(onCancel)}>Cancel</button>
    </form>
  );
}

const ROLES = [
  ['severity', 'Severity', 'How serious the event is'],
  ['timestamp', 'Time of the event', 'When it happened'],
  ['type', 'Event type', 'What kind of event this is'],
  ['service', 'Service', 'Which system produced it'],
];

function SchemaFromSample({ onDone, onCancel }) {
  const { adminTeams } = useAuth();
  const [text, setText] = useState('');
  const [inferred, setInferred] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [name, setName] = useState('');
  const [teamIdRaw, setTeamId] = useState('');
  const [priority, setPriority] = useState(10);
  const [paths, setPaths] = useState({ severity: '', timestamp: '', type: '', service: '' });
  const [tsFormat, setTsFormat] = useState('iso');
  const [suggested, setSuggested] = useState({});
  const [useMatch, setUseMatch] = useState(false);
  const [match, setMatch] = useState(emptyFilter());
  const [detected, setDetected] = useState('');
  const teamId = teamIdRaw || (adminTeams[0] && adminTeams[0].id) || '';

  const infer = async () => {
    setError(null);
    setBusy(true);
    const parsed = await parseSampleText(text);
    if (!parsed.ok) { setBusy(false); return setError(parsed.empty ? 'Paste a sample event first.' : `The sample could not be read: ${parsed.error}`); }
    setDetected(describeParsed(parsed));
    try {
      const r = (await api.post('/api/schemas/infer', { sample: parsed.value })) || {};
      let fields = asArray(r.fields);
      if (!fields.length) fields = flattenPaths(parsed.value);
      const m = r.mapping || {};
      setInferred({ fields });
      setSuggested(m);
      setPaths({
        severity: (m.severity && m.severity.path) || '',
        timestamp: (m.timestamp && m.timestamp.path) || '',
        type: (m.type && m.type.path) || '',
        service: (m.service && m.service.path) || '',
      });
      setTsFormat((m.timestamp && m.timestamp.format) || 'iso');
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  const save = async () => {
    if (!inferred) { toast.error('Detect the fields of the sample first.'); return false; }
    if (!name.trim()) { toast.error('Give the schema a name.'); return false; }
    setBusy(true);
    try {
      const mapping = {};
      ROLES.forEach(([role]) => {
        if (!paths[role]) return;
        const prev = suggested[role] && suggested[role].path === paths[role] ? suggested[role] : {};
        mapping[role] = { ...prev, path: paths[role] };
        if (role === 'timestamp') mapping[role].format = tsFormat;
      });
      let matchAst = null;
      if (useMatch) {
        const r = await resolveFilter(match, inferred.fields, { needAst: true });
        if (!r.empty) matchAst = r.ast;
      }
      await api.post('/api/schemas', { name: name.trim(), teamId, priority: Number(priority) || 0, match: matchAst, mapping, fields: inferred.fields });
      toast.success('Schema saved');
      setText(''); setInferred(null); setName(''); setUseMatch(false); setMatch(emptyFilter());
      onDone();
      return true;
    } catch (e) {
      toast.error(e);
      return false;
    } finally {
      setBusy(false);
    }
  };
  const guard = useUnsavedChanges(text.trim() !== '' || name.trim() !== '', save);

  return (
    <div>
      <div className="row wrap">
        <span className="muted">Start from an example:</span>
        {SAMPLES.map((s) => (
          <button type="button" key={s.id} className="btn small" onClick={() => { setText(sampleText(s.id)); setInferred(null); }}>{s.label}</button>
        ))}
      </div>
      <Field label="Paste one example event" hint="Any format works: JSON, a CSV header row plus a row, YAML, XML, key=value pairs, a syslog line or plain text.">
        <textarea className="mono" rows={8} value={text} onChange={(e) => { setText(e.target.value); setInferred(null); setDetected(''); }} placeholder={'{"type": "...", "severity": "..."}\nor  level=error service=payments msg="..."'} spellCheck={false} />
        {detected && <div className="muted small">{detected}</div>}
      </Field>
      <ErrorBox>{error}</ErrorBox>
      <div className="row">
        <button type="button" className="btn primary" onClick={infer} disabled={busy || !text.trim()}>{busy && !inferred ? 'Reading…' : 'Detect fields'}</button>
        <button type="button" className="btn" onClick={() => guard(onCancel)}>Cancel</button>
      </div>

      {inferred && (
        <div className="infer-result">
          <h3>Fields found ({inferred.fields.length})</h3>
          {inferred.fields.length === 0 ? <Empty>No fields were found in this sample.</Empty> : (
            <div className="table-wrap">
              <table className="table compact">
                <thead><tr><th>Field</th><th>Type</th><th>Example</th></tr></thead>
                <tbody>
                  {inferred.fields.map((f) => <tr key={f.path}><td><code>{f.path}</code></td><td>{f.type || '—'}</td><td className="wrap">{show(f.example)}</td></tr>)}
                </tbody>
              </table>
            </div>
          )}

          <h3>Which field means what?<Help text={'We have pre-selected our best guess. Leave "Detect automatically" if you are unsure.'} /></h3>
          <div className="row wrap">
            {ROLES.map(([role, label, hint]) => (
              <Field key={role} label={label} hint={hint}>
                <select value={paths[role]} onChange={(e) => setPaths({ ...paths, [role]: e.target.value })}>
                  <option value="">Detect automatically</option>
                  {inferred.fields.map((f) => <option key={f.path} value={f.path}>{f.path}{f.example != null ? ` (e.g. ${String(f.example).slice(0, 30)})` : ''}</option>)}
                </select>
              </Field>
            ))}
            {paths.timestamp && (
              <Field label="Time format">
                <select value={tsFormat} onChange={(e) => setTsFormat(e.target.value)}>
                  <option value="iso">Date text (ISO-8601, e.g. 2026-10-23T09:14:02Z)</option>
                  <option value="epoch_ms">Number of milliseconds since 1970</option>
                  <option value="epoch_s">Number of seconds since 1970</option>
                </select>
              </Field>
            )}
          </div>

          <label className="check">
            <input type="checkbox" checked={useMatch} onChange={(e) => setUseMatch(e.target.checked)} />
            Recognise this schema automatically when an event matches a condition
          </label>
          {useMatch && (
            <div className="sub">
              <FilterEditor value={match} onChange={setMatch} fields={inferred.fields} emptyText="No condition yet. Without one, the schema is only used when chosen as a source's default schema." />
              <Field label="Priority" hint="When several schemas match, the highest priority wins.">
                <input type="number" value={priority} onChange={(e) => setPriority(e.target.value)} style={{ width: 100 }} />
              </Field>
            </div>
          )}

          <div className="row wrap">
            <Field label="Schema name"><input type="text" value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Trade rejected v2" /></Field>
            <Field label="Owning team">
              <select value={teamId} onChange={(e) => setTeamId(e.target.value)}>
                {adminTeams.map((t) => <option key={t.id} value={t.id}>{t.name || t.id}</option>)}
              </select>
            </Field>
            <button type="button" className="btn primary align-end" onClick={save} disabled={busy}>Save schema</button>
          </div>
        </div>
      )}
    </div>
  );
}

function mappingSummary(mapping) {
  if (!mapping || typeof mapping !== 'object') return 'Detected automatically';
  const parts = ROLES.map(([role, label]) => {
    const m = mapping[role];
    if (!m) return null;
    if (m.path) return `${label}: ${m.path}`;
    if (m.const != null) return `${label}: always "${m.const}"`;
    return null;
  }).filter(Boolean);
  return parts.length ? parts.join(' · ') : 'Detected automatically';
}

const SUBTABS = [['schemas', 'Schemas'], ['sources', 'Sources']];

export default function Sources() {
  const { teamName, isAdminOf, adminTeams } = useAuth();
  const guard = useLeaveGuard();
  const [tab, setTab] = useState('schemas');
  const [adding, setAdding] = useState(false);
  const [sources, setSources] = useState(null);
  const [schemas, setSchemas] = useState(null);
  const [error, setError] = useState(null);
  const [confirm, confirmDialog] = useConfirm();

  const load = useCallback(() => {
    api.get('/api/sources').then((s) => { setSources(asArray(s)); setError(null); }).catch((e) => { setSources([]); setError(e.message); });
    api.get('/api/schemas').then((s) => setSchemas(asArray(s))).catch(() => setSchemas([]));
  }, []);
  useEffect(load, [load]);

  const switchTab = (next) => {
    if (next === tab) return;
    guard(() => { setTab(next); setAdding(false); });
  };

  const deleteSchema = async (s) => {
    const ok = await confirm({
      title: `Delete the schema "${s.name || s.id}"?`,
      body: 'Events that used it will have their severity, time, type and service guessed instead. This cannot be undone.',
      cancelLabel: 'Keep schema',
      confirmLabel: 'Delete schema',
    });
    if (!ok) return;
    try {
      await api.del(`/api/schemas/${encodeURIComponent(s.id)}`);
      toast.success('Schema deleted');
      load();
    } catch (e) {
      toast.error(e);
    }
  };

  const schemaList = schemas || [];
  const isAdmin = adminTeams.length > 0;
  const addLabel = tab === 'sources' ? 'Add source' : 'Add schema';

  return (
    <div>
      <div className="page-head"><h1>Sources and schemas</h1></div>
      <div className="tabs subtabs" role="tablist">
        {SUBTABS.map(([key, label]) => (
          <button key={key} type="button" role="tab" aria-selected={tab === key} className={`tab ${tab === key ? 'active' : ''}`} onClick={() => switchTab(key)}>
            {label}
          </button>
        ))}
      </div>
      <ErrorBox>{error}</ErrorBox>

      <div className="row">
        {isAdmin ? (
          !adding && <button type="button" className="btn primary" onClick={() => setAdding(true)}>{addLabel}</button>
        ) : (
          <span className="muted">Only team admins can add {tab === 'sources' ? 'sources' : 'schemas'}.</span>
        )}
      </div>

      {tab === 'sources' ? (
        <>
          {adding && (
            <Section title="Add a source">
              <CreateSource schemas={schemaList} onDone={() => { setAdding(false); load(); }} onCancel={() => setAdding(false)} />
            </Section>
          )}
          <Section title="Sources" hint="A source is a place events come from: a webhook you POST to, or a Kafka topic.">
            {sources === null ? <Empty>Loading sources…</Empty> : sources.length === 0 ? (
              <Empty title="No sources yet">Add one, then send it an event.</Empty>
            ) : (
              sources.map((s) => <SourceCard key={s.id} source={s} schemas={schemaList} reload={load} />)
            )}
          </Section>
        </>
      ) : (
        <>
          {adding && (
            <Section title="Add a schema from a sample" hint="Paste one example event. We work out its fields; you confirm which is which.">
              <SchemaFromSample onDone={() => { setAdding(false); load(); }} onCancel={() => setAdding(false)} />
            </Section>
          )}
          <Section title="Schemas" hint="A schema tells the service where to find severity, time, type and service in your events. Events with no schema still work: these are then guessed.">
            {schemas === null ? <Empty>Loading schemas…</Empty> : schemas.length === 0 ? (
              <Empty title="No schemas yet">Add one from a sample event.</Empty>
            ) : (
              <div className="table-wrap">
                <table className="table">
                  <thead><tr><th>Name</th><th>Team</th><th>Field mapping</th><th>Fields</th><th>Recognised by</th><th /></tr></thead>
                  <tbody>
                    {schemas.map((s) => (
                      <tr key={s.id}>
                        <td><strong>{s.name || s.id}</strong><div className="muted"><code>{s.id}</code></div></td>
                        <td>{teamName(s.teamId) || '—'}</td>
                        <td>{mappingSummary(s.mapping)}</td>
                        <td>{asArray(s.fields).length}</td>
                        <td>{s.match ? `Condition (priority ${s.priority != null ? s.priority : 0})` : 'Source default only'}</td>
                        <td className="right">
                          {isAdminOf(s.teamId) && <button type="button" className="btn small danger" onClick={() => deleteSchema(s)}>Delete</button>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Section>
        </>
      )}
      {confirmDialog}
    </div>
  );
}
