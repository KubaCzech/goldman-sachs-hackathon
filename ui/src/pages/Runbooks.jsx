import React, { useCallback, useEffect, useState } from 'react';
import { api } from '../api.js';
import { useAuth } from '../auth.jsx';
import { toast } from '../toast.js';
import { asArray, show } from '../util.js';
import { Empty, ErrorBox, Field, Section } from '../components/ui.jsx';

const EXAMPLE = `runbook: settlement-timeout
matches:
  type: WORKFLOW_FAILED
  error.code: TIMEOUT
steps:
  - check: Is counterparty-service healthy?
    action: http_get
    url: http://counterparty-service/health
  - if: service is healthy
    action: retry_workflow
    workflowId: "{{workflowId}}"
  - check: If the retry fails twice, escalate to the on-call lead
`;

function StepsPreview({ parsed }) {
  const steps = asArray(parsed && parsed.steps);
  const matches = parsed && parsed.matches && typeof parsed.matches === 'object' ? Object.entries(parsed.matches) : [];
  return (
    <div className="preview">
      <div className="muted">
        Read as: <strong>{(parsed && parsed.format) || 'text'}</strong>
        {parsed && parsed.name ? <> · Name: <strong>{parsed.name}</strong></> : null}
      </div>
      {matches.length > 0 && (
        <div className="muted">Used automatically when: {matches.map(([k, v]) => `${k} = ${show(v)}`).join(' and ')}</div>
      )}
      {steps.length === 0 ? <Empty>No steps were found.</Empty> : (
        <ol className="steps">
          {steps.map((s, i) => (
            <li className="step" key={i}>
              <div className="step-text">{s.text || '(no description)'}</div>
              {s.condition && <div className="step-line"><span className="tag">When</span> {s.condition}</div>}
              {s.action && (
                <div className="step-line">
                  <span className="tag">Action</span>{' '}
                  <code>{typeof s.action === 'string' ? s.action : `${s.action.type || 'action'}${s.action.params ? ` ${JSON.stringify(s.action.params)}` : ''}`}</code>
                  {String(s.approval || '').toLowerCase() === 'required' && <span className="badge warn">needs approval</span>}
                </div>
              )}
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

function RunbookEditor({ runbook, onClose }) {
  const { adminTeams, teamName } = useAuth();
  const isNew = !runbook;
  const [name, setName] = useState((runbook && runbook.name) || '');
  const [teamIdRaw, setTeamId] = useState((runbook && runbook.teamId) || '');
  const [format, setFormat] = useState('');
  const [content, setContent] = useState((runbook && runbook.content) || '');
  const [parsed, setParsed] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const teamId = teamIdRaw || (adminTeams[0] && adminTeams[0].id) || '';

  const body = () => {
    const b = { teamId, content };
    if (format) b.format = format;
    if (name.trim()) b.name = name.trim();
    return b;
  };

  const preview = async () => {
    setBusy(true);
    setError(null);
    try {
      const b = { content };
      if (format) b.format = format;
      setParsed((await api.post('/api/runbooks/parse', b)) || {});
    } catch (e) {
      setParsed(null);
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  const save = async () => {
    if (!content.trim()) return toast.error('Paste the runbook text first.');
    if (!teamId) return toast.error('Choose the team that owns this runbook.');
    setBusy(true);
    try {
      if (isNew) await api.post('/api/runbooks', body());
      else await api.put(`/api/runbooks/${encodeURIComponent(runbook.id)}`, body());
      toast.success(isNew ? 'Runbook created' : 'Runbook saved');
      onClose(true);
    } catch (e) {
      toast.error(e);
    } finally {
      setBusy(false);
    }
  };

  const teamOptions = adminTeams.some((t) => t.id === teamId) || !teamId ? adminTeams : [...adminTeams, { id: teamId, name: teamName(teamId) }];

  return (
    <Section title={isNew ? 'New runbook' : `Edit runbook: ${runbook.name || runbook.id}`} hint="Paste the instructions in any form: YAML, Markdown, a wiki page, or plain text. Nothing is lost; the original text is always kept.">
      <div className="row wrap">
        <Field label="Name (optional)" hint="Taken from the text when left empty.">
          <input type="text" value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. settlement-timeout" />
        </Field>
        <Field label="Owning team">
          <select value={teamId} onChange={(e) => setTeamId(e.target.value)} disabled={!isNew}>
            {teamOptions.map((t) => <option key={t.id} value={t.id}>{t.name || t.id}</option>)}
          </select>
        </Field>
        <Field label="Format">
          <select value={format} onChange={(e) => setFormat(e.target.value)}>
            <option value="">Detect automatically</option>
            <option value="yaml">YAML (steps with actions)</option>
            <option value="markdown">Markdown (a list of steps)</option>
            <option value="text">Plain text</option>
          </select>
        </Field>
      </div>
      <Field label="Runbook text">
        <textarea className="mono" rows={14} value={content} onChange={(e) => { setContent(e.target.value); setParsed(null); }} placeholder="Paste the runbook here" spellCheck={false} />
      </Field>
      {isNew && !content && <button type="button" className="btn small ghost" onClick={() => setContent(EXAMPLE)}>Fill in an example</button>}
      <ErrorBox>{error}</ErrorBox>
      <div className="row">
        <button type="button" className="btn" onClick={preview} disabled={busy || !content.trim()}>Preview parse</button>
        <button type="button" className="btn primary" onClick={save} disabled={busy}>Save runbook</button>
        <button type="button" className="btn" onClick={() => onClose(false)}>Cancel</button>
      </div>
      {parsed && (
        <>
          <h3>How this will be read</h3>
          <StepsPreview parsed={parsed} />
        </>
      )}
    </Section>
  );
}

export default function Runbooks() {
  const { teamName, isAdminOf, adminTeams } = useAuth();
  const [runbooks, setRunbooks] = useState(null);
  const [error, setError] = useState(null);
  const [editing, setEditing] = useState(undefined); // undefined = list, null = new, object = edit

  const load = useCallback(() => {
    api.get('/api/runbooks').then((r) => { setRunbooks(asArray(r)); setError(null); }).catch((e) => { setRunbooks([]); setError(e.message); });
  }, []);
  useEffect(load, [load]);

  const remove = async (r) => {
    if (!window.confirm(`Delete the runbook "${r.name || r.id}"?`)) return;
    try {
      await api.del(`/api/runbooks/${encodeURIComponent(r.id)}`);
      toast.success('Runbook deleted');
      load();
    } catch (e) {
      toast.error(e);
    }
  };

  return (
    <div>
      <div className="page-head">
        <h1>Runbooks</h1>
        {editing === undefined && (adminTeams.length > 0 ? (
          <button type="button" className="btn primary" onClick={() => setEditing(null)}>New runbook</button>
        ) : (
          <span className="muted">Only team admins can add runbooks.</span>
        ))}
      </div>
      <ErrorBox>{error}</ErrorBox>

      {editing !== undefined && (
        <RunbookEditor key={editing ? editing.id : 'new'} runbook={editing} onClose={(changed) => { setEditing(undefined); if (changed) load(); }} />
      )}

      {editing === undefined && (
        runbooks === null ? <Empty>Loading runbooks…</Empty> : runbooks.length === 0 ? (
          <Empty title="No runbooks yet">A runbook is the list of steps to follow when an alert fires. Paste one in any format to get started.</Empty>
        ) : (
          <div className="card">
            {runbooks.map((r) => (
              <div className="item" key={r.id}>
                <div className="item-head">
                  <div>
                    <strong>{r.name || r.id}</strong> <span className="badge">{r.format || 'text'}</span>
                    <div className="muted">
                      Team {teamName(r.teamId) || '—'} · {asArray(r.steps).length} step{asArray(r.steps).length === 1 ? '' : 's'}
                      {r.version != null ? ` · version ${r.version}` : ''}
                    </div>
                  </div>
                  {isAdminOf(r.teamId) && (
                    <div className="row">
                      <button type="button" className="btn small" onClick={() => setEditing(r)}>Edit</button>
                      <button type="button" className="btn small danger" onClick={() => remove(r)}>Delete</button>
                    </div>
                  )}
                </div>
                <details>
                  <summary>Show steps</summary>
                  <StepsPreview parsed={r} />
                </details>
              </div>
            ))}
          </div>
        )
      )}
    </div>
  );
}
