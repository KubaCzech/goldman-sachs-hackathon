import React, { useCallback, useEffect, useState } from 'react';
import { api } from '../api.js';
import { useAuth } from '../auth.jsx';
import { toast } from '../toast.js';
import { asArray } from '../util.js';
import { Field, Help, Section } from './ui.jsx';

// Built into the service; every other action type is configuration (an HTTP call with templates).
const BUILT_IN = [
  { type: 'http_get', method: 'GET', urlTemplate: '{{url}}', note: 'Calls the step\'s url. Used for health checks.' },
  { type: 'http_post', method: 'POST', urlTemplate: '{{url}}', bodyTemplate: '{{body}}', note: 'Posts the step\'s body to its url (a webhook).' },
];

const placeholdersIn = (...texts) => [...new Set(texts.join(' ').match(/\{\{\s*[\w.]+\s*\}\}/g) || [])].map((p) => p.replace(/[{}\s]/g, ''));

function yamlFor(a) {
  const params = placeholdersIn(a.urlTemplate || '', a.bodyTemplate || '');
  const lines = [`- check: Describe what this step does`, `  action: ${a.type}`];
  params.forEach((p) => lines.push(`  ${p}: "{{${p}}}"`));
  lines.push('  approval: required');
  return lines.join('\n');
}

const EMPTY = { type: '', method: 'POST', urlTemplate: '', bodyTemplate: '{}' };

/** What a runbook step can do, and (platform admins) adding a new kind of action without code. */
export default function ActionTypes() {
  const { isPlatformAdmin } = useAuth();
  const [types, setTypes] = useState(null);
  const [form, setForm] = useState(EMPTY);
  const [busy, setBusy] = useState(false);
  const load = useCallback(() => {
    api.get('/api/action-types').then((t) => setTypes(asArray(t))).catch(() => setTypes([]));
  }, []);
  useEffect(load, [load]);

  const save = async (e) => {
    e.preventDefault();
    if (!/^[a-z][a-z0-9_]*$/.test(form.type)) { toast.error('Name the action in lower case with underscores, e.g. page_oncall.'); return; }
    if (!form.urlTemplate.trim()) { toast.error('Give the URL the action calls.'); return; }
    setBusy(true);
    try {
      await api.post('/api/action-types', form);
      toast.success(`Action "${form.type}" saved. Runbook steps can use it now.`);
      setForm(EMPTY);
      load();
    } catch (err) {
      toast.error(err);
    } finally {
      setBusy(false);
    }
  };

  const all = [...BUILT_IN.map((a) => ({ ...a, builtIn: true })), ...asArray(types)];
  return (
    <Section
      title="Actions you can use in steps"
      hint="A runbook step with an 'action' is run by the service when someone clicks Run on the alert. Values in {{ }} are filled from the step's own settings first, then from the alert's event. The target host must be on the allowlist that a platform admin keeps. Steps with 'approval: required' wait for a team admin."
    >
      <table className="table">
        <thead>
          <tr><th>Action</th><th>What it calls</th><th>Use it in a step (YAML)</th></tr>
        </thead>
        <tbody>
          {all.map((a) => (
            <tr key={a.type}>
              <td><code>{a.type}</code>{a.builtIn && <span className="badge">built in</span>}{a.note && <Help text={a.note} />}</td>
              <td><code>{a.method || 'POST'} {a.urlTemplate}</code>{a.bodyTemplate && a.bodyTemplate !== '{}' && <div className="muted"><code>{a.bodyTemplate}</code></div>}</td>
              <td><pre className="mono small-pre">{yamlFor(a)}</pre></td>
            </tr>
          ))}
        </tbody>
      </table>
      {isPlatformAdmin ? (
        <form onSubmit={save} className="row wrap">
          <Field label="New action name" hint="Lower case with underscores. Runbook steps refer to it as action: <name>.">
            <input type="text" value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value.trim() })} placeholder="page_oncall" />
          </Field>
          <Field label="Method">
            <select value={form.method} onChange={(e) => setForm({ ...form, method: e.target.value })}>
              {['POST', 'PUT', 'GET', 'DELETE'].map((m) => <option key={m} value={m}>{m}</option>)}
            </select>
          </Field>
          <Field label="URL" hint="Can use placeholders, e.g. http://pager/teams/{{team}}/page. The host must be on the allowlist." className="grow">
            <input type="text" value={form.urlTemplate} onChange={(e) => setForm({ ...form, urlTemplate: e.target.value })} placeholder="http://pager/teams/{{team}}/page" />
          </Field>
          <Field label="Body (JSON template)" className="grow">
            <input type="text" className="mono" value={form.bodyTemplate} onChange={(e) => setForm({ ...form, bodyTemplate: e.target.value })} />
          </Field>
          <button type="submit" className="btn primary align-end" disabled={busy}>Add action</button>
        </form>
      ) : (
        <div className="muted">A platform admin can add new kinds of action here, without any code.</div>
      )}
    </Section>
  );
}
