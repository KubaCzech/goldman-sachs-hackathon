import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api.js';
import { useAuth } from '../auth.jsx';
import { toast } from '../toast.js';
import { SAMPLES, sampleText } from '../samples.js';
import { ALL_SEVERITIES, CHANNELS, CHANNEL_LABELS, asArray, flattenPaths, mergeFields, sevColor, show, tryParseJson } from '../util.js';
import FilterEditor, { conditionsToAst, filterFromStored } from '../components/FilterEditor.jsx';
import { Empty, ErrorBox, Field, PathList, Section, SeverityBadge, TemplateText } from '../components/ui.jsx';

const DEFAULT_TITLE = '{{type}} from {{service}}';

function targetLabel(t, teamName, userName) {
  if (!t) return '';
  if (t.type === 'user') return `Person: ${userName(t.id)}`;
  if (t.type === 'oncall') return `On-call of ${teamName(t.id)}`;
  return `Team: ${teamName(t.id)}`;
}

function formFromRule(rule, defaultTeamId) {
  const r = rule || {};
  const p = r.presentation || {};
  return {
    name: r.name || '',
    teamId: r.teamId || defaultTeamId || '',
    enabled: r.enabled !== false,
    sourceIds: asArray(r.sourceIds),
    groupBy: asArray(r.groupBy),
    windowOn: !!r.window,
    windowCount: (r.window && r.window.count) || 5,
    windowSeconds: (r.window && r.window.seconds) || 300,
    severity: r.severity || '',
    renotifyMinutes: r.renotifyMinutes || 0,
    targets: asArray(r.targets),
    useRecipientPrefs: !asArray(r.channels).length,
    channels: asArray(r.channels),
    titleTemplate: p.titleTemplate != null ? p.titleTemplate : rule ? '' : DEFAULT_TITLE,
    bodyTemplate: p.bodyTemplate || '',
    presFields: asArray(p.fields),
    useColor: !!p.color,
    color: p.color || '#d32f2f',
    runbookId: r.runbookId || '',
  };
}

function RuleEditor({ rule, onClose, sources, runbooks, apiFields }) {
  const { teams, users, adminTeams, teamName, userName } = useAuth();
  const isNew = !rule || !rule.id;
  const [form, setForm] = useState(() => formFromRule(rule, adminTeams[0] && adminTeams[0].id));
  const [filter, setFilter] = useState(() => filterFromStored(rule && rule.filter, rule && rule.filterSql));
  const [resolveSql, setResolveSql] = useState('');
  const [resolveTouched, setResolveTouched] = useState(false);
  const [sample, setSample] = useState(() => sampleText(SAMPLES[0].id));
  const [sampleSourceId, setSampleSourceId] = useState('');
  const [preview, setPreview] = useState(null);
  const [previewError, setPreviewError] = useState(null);
  const [previewBusy, setPreviewBusy] = useState(false);
  const [saving, setSaving] = useState(false);
  const [newTarget, setNewTarget] = useState({ type: 'team', id: '' });
  const [activeTpl, setActiveTpl] = useState('title');
  const titleRef = useRef(null);
  const bodyRef = useRef(null);
  const parseCache = useRef({});
  const set = (patch) => setForm((f) => ({ ...f, ...patch }));

  // resolveWhen is stored as an AST; show it as SQL text.
  useEffect(() => {
    if (!rule || !rule.resolveWhen) return;
    api.post('/api/filters/print', { ast: rule.resolveWhen })
      .then((r) => {
        const sql = (r && r.sql) || '';
        parseCache.current[sql.trim()] = rule.resolveWhen;
        setResolveSql(sql);
      })
      .catch((e) => toast.error(`Could not load the "resolve when" condition: ${e.message}`));
  }, [rule]);

  const parsedSample = useMemo(() => tryParseJson(sample), [sample]);
  const fields = useMemo(() => {
    const ev = parsedSample.ok ? (Array.isArray(parsedSample.value) ? parsedSample.value[0] : parsedSample.value) : null;
    return mergeFields(apiFields, ev ? flattenPaths(ev) : []);
  }, [apiFields, parsedSample]);

  const teamOptions = useMemo(() => {
    const list = isNew ? adminTeams : teams;
    if (form.teamId && !list.some((t) => t.id === form.teamId)) return [...list, { id: form.teamId, name: teamName(form.teamId) }];
    return list;
  }, [isNew, adminTeams, teams, form.teamId, teamName]);

  const buildRule = useCallback(async () => {
    const r = { ...(rule || {}) };
    ['canEdit', 'teamName', 'allowedSources', 'filter', 'filterSql'].forEach((k) => delete r[k]);
    Object.assign(r, {
      name: form.name.trim(),
      teamId: form.teamId,
      enabled: form.enabled,
      sourceIds: form.sourceIds,
      groupBy: form.groupBy,
      window: form.windowOn ? { count: Math.max(1, Number(form.windowCount) || 1), seconds: Math.max(1, Number(form.windowSeconds) || 1) } : null,
      severity: form.severity || null,
      renotifyMinutes: Math.max(0, Number(form.renotifyMinutes) || 0),
      targets: form.targets,
      channels: form.useRecipientPrefs || form.channels.length === 0 ? null : form.channels,
      presentation: {
        titleTemplate: form.titleTemplate,
        bodyTemplate: form.bodyTemplate,
        fields: form.presFields,
        color: form.useColor ? form.color : null,
      },
      runbookId: form.runbookId || null,
    });
    if (filter.mode === 'builder') r.filter = conditionsToAst(filter.conditions, fields);
    else r.filterSql = filter.sql || '';
    const rs = resolveSql.trim();
    // Keep the stored condition if it could not be shown as SQL and the user never edited it.
    if (!rs && !resolveTouched && rule && rule.resolveWhen) r.resolveWhen = rule.resolveWhen;
    else if (!rs) r.resolveWhen = null;
    else {
      if (!parseCache.current[rs]) parseCache.current[rs] = (await api.post('/api/filters/parse', { sql: rs })).ast;
      r.resolveWhen = parseCache.current[rs];
    }
    return r;
  }, [rule, form, filter, resolveSql, resolveTouched, fields]);

  // Live preview, debounced.
  const seq = useRef(0);
  useEffect(() => {
    const mine = ++seq.current;
    if (!parsedSample.ok) {
      setPreview(null);
      setPreviewError(sample.trim() ? `The sample is not valid JSON: ${parsedSample.error}` : 'Paste or pick a sample event to see a preview.');
      return undefined;
    }
    const timer = setTimeout(async () => {
      setPreviewBusy(true);
      try {
        const body = { rule: await buildRule(), sample: parsedSample.value };
        if (sampleSourceId) body.sourceId = sampleSourceId;
        const res = await api.post('/api/rules/preview', body);
        if (mine === seq.current) {
          setPreview(res || {});
          setPreviewError(null);
        }
      } catch (e) {
        if (mine === seq.current) {
          setPreview(null);
          setPreviewError(e.message);
        }
      } finally {
        if (mine === seq.current) setPreviewBusy(false);
      }
    }, 500);
    return () => clearTimeout(timer);
  }, [buildRule, parsedSample, sample, sampleSourceId]);

  const insertField = (path) => {
    if (!path) return;
    const key = activeTpl === 'body' ? 'bodyTemplate' : 'titleTemplate';
    const el = activeTpl === 'body' ? bodyRef.current : titleRef.current;
    const current = form[key] || '';
    const start = el && el.selectionStart != null ? el.selectionStart : current.length;
    const end = el && el.selectionEnd != null ? el.selectionEnd : start;
    const token = `{{${path}}}`;
    set({ [key]: current.slice(0, start) + token + current.slice(end) });
    requestAnimationFrame(() => {
      if (!el) return;
      el.focus();
      const pos = start + token.length;
      try {
        el.setSelectionRange(pos, pos);
      } catch {
        /* not supported for this input type */
      }
    });
  };

  const toggleIn = (key, value) => {
    const list = form[key];
    set({ [key]: list.includes(value) ? list.filter((x) => x !== value) : [...list, value] });
  };

  const addTarget = () => {
    if (!newTarget.id) return;
    if (!form.targets.some((t) => t.type === newTarget.type && t.id === newTarget.id)) {
      set({ targets: [...form.targets, { type: newTarget.type, id: newTarget.id }] });
    }
    setNewTarget({ ...newTarget, id: '' });
  };

  const save = async () => {
    if (!form.name.trim()) return toast.error('Give the rule a name.');
    if (!form.teamId) return toast.error('Choose the team that owns this rule.');
    setSaving(true);
    try {
      const body = await buildRule();
      if (isNew) await api.post('/api/rules', body);
      else await api.put(`/api/rules/${encodeURIComponent(rule.id)}`, body);
      toast.success(isNew ? 'Rule created' : 'Rule saved');
      onClose(true);
    } catch (e) {
      toast.error(e);
    } finally {
      setSaving(false);
    }
  };

  const targetOptions = newTarget.type === 'user'
    ? users.map((u) => ({ id: u.id, label: u.displayName || u.username || u.id }))
    : teams.map((t) => ({ id: t.id, label: t.name || t.id }));
  const previewFields = preview && preview.fields && typeof preview.fields === 'object' ? Object.entries(preview.fields) : [];
  const unresolved = asArray(preview && preview.unresolved);

  return (
    <div>
      <div className="page-head">
        <h1>{isNew ? 'New rule' : `Edit rule: ${rule.name || rule.id}`}</h1>
        <div className="row">
          <button type="button" className="btn" onClick={() => onClose(false)}>Cancel</button>
          <button type="button" className="btn primary" onClick={save} disabled={saving}>{saving ? 'Saving…' : 'Save rule'}</button>
        </div>
      </div>

      <div className="split">
        <div className="split-main">
          <Section title="1. Basics">
            <div className="row wrap">
              <Field label="Rule name" className="grow">
                <input type="text" value={form.name} onChange={(e) => set({ name: e.target.value })} placeholder="e.g. Prod settlement failures" />
              </Field>
              <Field label="Owning team">
                <select value={form.teamId} onChange={(e) => set({ teamId: e.target.value })} disabled={!isNew}>
                  {!form.teamId && <option value="">Choose a team…</option>}
                  {teamOptions.map((t) => <option key={t.id} value={t.id}>{t.name || t.id}</option>)}
                </select>
              </Field>
              <label className="check align-end">
                <input type="checkbox" checked={form.enabled} onChange={(e) => set({ enabled: e.target.checked })} /> Enabled
              </label>
            </div>
            <div className="field">
              <span className="field-label">Sources to watch</span>
              {sources.length === 0 ? <span className="muted">No sources available. Add one on the Sources page.</span> : (
                <div className="checks">
                  {sources.map((s) => (
                    <label className="check" key={s.id}>
                      <input type="checkbox" checked={form.sourceIds.includes(s.id)} onChange={() => toggleIn('sourceIds', s.id)} />
                      {s.name || s.id}
                    </label>
                  ))}
                </div>
              )}
              <span className="hint">{form.sourceIds.length === 0 ? 'Nothing ticked: the rule watches every source the team may read.' : 'Only the ticked sources are watched.'}</span>
            </div>
          </Section>

          <Section title="2. Which events raise an alert?" hint="Events matching all of these conditions raise the alert.">
            <FilterEditor value={filter} onChange={setFilter} fields={fields} emptyText="No conditions yet. Every event from the watched sources raises an alert." />
          </Section>

          <Section title="3. Grouping" hint="Repeated events are folded into one alert with a counter, instead of one alert each.">
            <div className="field">
              <span className="field-label">Group events that have the same</span>
              <PathList value={form.groupBy} onChange={(v) => set({ groupBy: v })} fields={fields} emptyText="Nothing chosen: all matching events become one alert for this rule." />
            </div>
            <label className="check">
              <input type="checkbox" checked={form.windowOn} onChange={(e) => set({ windowOn: e.target.checked })} />
              Only alert after several events in a short time
            </label>
            {form.windowOn && (
              <div className="row sub">
                <span>Alert after</span>
                <input type="number" min="1" value={form.windowCount} onChange={(e) => set({ windowCount: e.target.value })} style={{ width: 80 }} aria-label="Number of events" />
                <span>events within</span>
                <input type="number" min="1" value={form.windowSeconds} onChange={(e) => set({ windowSeconds: e.target.value })} style={{ width: 90 }} aria-label="Seconds" />
                <span>seconds</span>
              </div>
            )}
            <Field label="Resolve automatically when (optional)" hint="An SQL condition. An event matching it closes the alert for the same group, e.g. type = 'WORKFLOW_SUCCEEDED'.">
              <input type="text" className="mono" value={resolveSql} onChange={(e) => { setResolveTouched(true); setResolveSql(e.target.value); }} placeholder="Leave empty to resolve by hand" spellCheck={false} />
            </Field>
            <div className="row wrap">
              <Field label="Severity">
                <select value={form.severity} onChange={(e) => set({ severity: e.target.value })}>
                  <option value="">Take from the event</option>
                  {ALL_SEVERITIES.filter((s) => s !== 'unknown').map((s) => <option key={s} value={s}>Always {s}</option>)}
                </select>
              </Field>
              <Field label="Remind again every (minutes)" hint="0 = notify once per alert.">
                <input type="number" min="0" value={form.renotifyMinutes} onChange={(e) => set({ renotifyMinutes: e.target.value })} style={{ width: 110 }} />
              </Field>
            </div>
          </Section>

          <Section title="4. Who is notified, and how?">
            <div className="field">
              <span className="field-label">Recipients</span>
              <div className="chips">
                {form.targets.length === 0 && <span className="muted">Nobody yet. The alert still appears in the team's alert feed.</span>}
                {form.targets.map((t, i) => (
                  <span className="chip" key={`${t.type}-${t.id}`}>
                    {targetLabel(t, teamName, userName)}
                    <button type="button" aria-label="Remove recipient" onClick={() => set({ targets: form.targets.filter((_, j) => j !== i) })}>×</button>
                  </span>
                ))}
              </div>
              <div className="row wrap">
                <select value={newTarget.type} onChange={(e) => setNewTarget({ type: e.target.value, id: '' })} aria-label="Recipient type">
                  <option value="team">A whole team</option>
                  <option value="oncall">The on-call person of a team</option>
                  <option value="user">One person</option>
                </select>
                <select value={newTarget.id} onChange={(e) => setNewTarget({ ...newTarget, id: e.target.value })} aria-label="Recipient">
                  <option value="">{newTarget.type === 'user' ? 'Choose a person…' : 'Choose a team…'}</option>
                  {targetOptions.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
                </select>
                <button type="button" className="btn" onClick={addTarget} disabled={!newTarget.id}>Add recipient</button>
              </div>
            </div>
            <div className="field">
              <span className="field-label">Channels</span>
              <label className="check">
                <input type="radio" name="chmode" checked={form.useRecipientPrefs} onChange={() => set({ useRecipientPrefs: true })} />
                Use each recipient's own preferences
              </label>
              <label className="check">
                <input type="radio" name="chmode" checked={!form.useRecipientPrefs} onChange={() => set({ useRecipientPrefs: false })} />
                Always use these channels:
              </label>
              {!form.useRecipientPrefs && (
                <div className="checks sub">
                  {CHANNELS.map((c) => (
                    <label className="check" key={c}>
                      <input type="checkbox" checked={form.channels.includes(c)} onChange={() => toggleIn('channels', c)} /> {CHANNEL_LABELS[c]}
                    </label>
                  ))}
                  {form.channels.length === 0 && <span className="hint">Nothing ticked: recipient preferences are used.</span>}
                </div>
              )}
            </div>
          </Section>

          <Section title="5. What the alert looks like">
            <div className="insert-bar">
              <span>Insert a field into the <strong>{activeTpl === 'body' ? 'message' : 'title'}</strong> at the cursor:</span>
              <select value="" onChange={(e) => insertField(e.target.value)} aria-label="Insert field">
                <option value="">Choose a field…</option>
                {fields.map((f) => <option key={f.path} value={f.path}>{f.path}{f.example != null && f.example !== '' ? ` (e.g. ${String(f.example).slice(0, 30)})` : ''}</option>)}
              </select>
            </div>
            <Field label="Title" hint="Click in the title or message, then choose a field above. Fields appear as {{name}} and are replaced by the event's value.">
              <input type="text" ref={titleRef} value={form.titleTemplate} onFocus={() => setActiveTpl('title')} onChange={(e) => set({ titleTemplate: e.target.value })} placeholder={DEFAULT_TITLE} />
            </Field>
            <Field label="Message">
              <textarea ref={bodyRef} rows={4} value={form.bodyTemplate} onFocus={() => setActiveTpl('body')} onChange={(e) => set({ bodyTemplate: e.target.value })} placeholder="Optional longer text" />
            </Field>
            <div className="field">
              <span className="field-label">Fields to show on the alert</span>
              <PathList value={form.presFields} onChange={(v) => set({ presFields: v })} fields={fields} emptyText="No extra fields are shown." />
            </div>
            <div className="row">
              <label className="check">
                <input type="checkbox" checked={form.useColor} onChange={(e) => set({ useColor: e.target.checked })} /> Use a custom colour
              </label>
              {form.useColor ? (
                <input type="color" value={form.color} onChange={(e) => set({ color: e.target.value })} aria-label="Alert colour" />
              ) : (
                <span className="hint">The colour follows the severity.</span>
              )}
            </div>
          </Section>

          <Section title="6. Runbook" hint="Instructions shown with the alert and sent with notifications.">
            <Field label="Runbook">
              <select value={form.runbookId} onChange={(e) => set({ runbookId: e.target.value })}>
                <option value="">None (suggest one automatically)</option>
                {runbooks.map((r) => <option key={r.id} value={r.id}>{r.name || r.id}{r.teamId ? ` (${teamName(r.teamId)})` : ''}</option>)}
                {form.runbookId && !runbooks.some((r) => r.id === form.runbookId) && <option value={form.runbookId}>{form.runbookId}</option>}
              </select>
            </Field>
          </Section>

          <div className="row">
            <button type="button" className="btn primary" onClick={save} disabled={saving}>{saving ? 'Saving…' : 'Save rule'}</button>
            <button type="button" className="btn" onClick={() => onClose(false)}>Cancel</button>
          </div>
        </div>

        <aside className="split-side">
          <div className="card sticky">
            <div className="card-head"><h2>Live preview</h2>{previewBusy && <span className="muted">Updating…</span>}</div>
            <p className="muted">See what this rule does with an example event.</p>
            <div className="row wrap">
              {SAMPLES.map((s) => <button type="button" key={s.id} className="btn small" onClick={() => setSample(sampleText(s.id))}>{s.label}</button>)}
            </div>
            <Field label="Sample event (JSON)">
              <textarea className="mono" rows={9} value={sample} onChange={(e) => setSample(e.target.value)} spellCheck={false} />
            </Field>
            <Field label="Pretend it came from">
              <select value={sampleSourceId} onChange={(e) => setSampleSourceId(e.target.value)}>
                <option value="">Any source</option>
                {sources.map((s) => <option key={s.id} value={s.id}>{s.name || s.id}</option>)}
              </select>
            </Field>

            {previewError && <div className="error-box">{previewError}</div>}
            {preview && (
              <div className="preview">
                <div className={`match ${preview.matches ? 'yes' : 'no'}`}>
                  {preview.matches ? 'Yes: this event would raise an alert' : 'No: this event does not match the rule'}
                </div>
                <div className="preview-alert" style={{ borderLeftColor: form.useColor ? form.color : sevColor(preview.severity) }}>
                  <div className="alert-title"><TemplateText text={preview.title || '(no title)'} /></div>
                  <div className="alert-meta"><SeverityBadge severity={preview.severity} /></div>
                  {preview.body && <p className="alert-body"><TemplateText text={preview.body} /></p>}
                  {previewFields.length > 0 && (
                    <table className="table kv compact">
                      <tbody>{previewFields.map(([k, v]) => <tr key={k}><th>{k}</th><td><TemplateText text={show(v)} /></td></tr>)}</tbody>
                    </table>
                  )}
                </div>
                {unresolved.length > 0 && (
                  <div className="warn-box">
                    These fields were not found in the sample:{' '}
                    {unresolved.map((u) => <mark className="missing" key={show(u)}>{show(u)}</mark>)}
                  </div>
                )}
                {preview.alertKey && <div className="hint">Group key: <code>{preview.alertKey}</code></div>}
              </div>
            )}
          </div>
        </aside>
      </div>
    </div>
  );
}

export default function Rules() {
  const { teamName, userName, adminTeams } = useAuth();
  const [rules, setRules] = useState(null);
  const [error, setError] = useState(null);
  const [editing, setEditing] = useState(undefined); // undefined = list, null = new, object = edit
  const [sources, setSources] = useState([]);
  const [runbooks, setRunbooks] = useState([]);
  const [fields, setFields] = useState([]);

  const load = useCallback(() => {
    api.get('/api/rules').then((r) => { setRules(asArray(r)); setError(null); }).catch((e) => { setRules([]); setError(e.message); });
  }, []);

  useEffect(() => {
    load();
    api.get('/api/sources').then((s) => setSources(asArray(s))).catch(() => {});
    api.get('/api/runbooks').then((s) => setRunbooks(asArray(s))).catch(() => {});
    api.get('/api/fields').then((s) => setFields(asArray(s))).catch(() => {});
  }, [load]);

  const remove = async (r) => {
    if (!window.confirm(`Delete the rule "${r.name || r.id}"?`)) return;
    try {
      await api.del(`/api/rules/${encodeURIComponent(r.id)}`);
      toast.success('Rule deleted');
      load();
    } catch (e) {
      toast.error(e);
    }
  };

  if (editing !== undefined) {
    return (
      <RuleEditor
        rule={editing}
        sources={sources}
        runbooks={runbooks}
        apiFields={fields}
        onClose={(changed) => { setEditing(undefined); if (changed) load(); }}
      />
    );
  }

  const groups = {};
  (rules || []).forEach((r) => {
    const key = r.teamId || '';
    (groups[key] = groups[key] || { name: r.teamName || teamName(r.teamId) || 'No team', rules: [] }).rules.push(r);
  });

  return (
    <div>
      <div className="page-head">
        <h1>Rules</h1>
        {adminTeams.length > 0 ? (
          <button type="button" className="btn primary" onClick={() => setEditing(null)}>New rule</button>
        ) : (
          <span className="muted">Only team admins can create rules.</span>
        )}
      </div>
      <ErrorBox>{error}</ErrorBox>
      {rules === null ? <Empty>Loading rules…</Empty> : rules.length === 0 ? (
        <Empty title="No rules yet">A rule decides which events become alerts and who is told.</Empty>
      ) : (
        Object.keys(groups).sort().map((key) => (
          <Section key={key} title={groups[key].name}>
            {groups[key].rules.map((r) => (
              <div className="item" key={r.id}>
                <div className="item-head">
                  <div>
                    <strong>{r.name || r.id}</strong>{' '}
                    <span className={`badge ${r.enabled === false ? 'off' : 'on'}`}>{r.enabled === false ? 'Disabled' : 'Enabled'}</span>
                    {r.severity && <> <SeverityBadge severity={r.severity} /></>}
                  </div>
                  {r.canEdit ? (
                    <div className="row">
                      <button type="button" className="btn small" onClick={() => setEditing(r)}>Edit</button>
                      <button type="button" className="btn small danger" onClick={() => remove(r)}>Delete</button>
                    </div>
                  ) : (
                    <span className="muted">Read only</span>
                  )}
                </div>
                <div className="rule-summary">
                  <div><span className="tag">When</span> <code>{r.filterSql || 'every event'}</code></div>
                  <div>
                    <span className="tag">Sources</span>{' '}
                    {asArray(r.sourceIds).length ? asArray(r.sourceIds).join(', ') : 'all the team may read'}
                    {asArray(r.groupBy).length > 0 && <> · <span className="tag">Grouped by</span> {r.groupBy.join(', ')}</>}
                    {r.window && <> · after {r.window.count} events in {r.window.seconds}s</>}
                  </div>
                  <div>
                    <span className="tag">Notify</span>{' '}
                    {asArray(r.targets).length ? asArray(r.targets).map((t) => targetLabel(t, teamName, userName)).join('; ') : 'nobody (feed only)'}
                    {' · '}
                    {asArray(r.channels).length ? asArray(r.channels).map((c) => CHANNEL_LABELS[c] || c).join(', ') : 'recipient preferences'}
                    {r.runbookId && <> · <span className="tag">Runbook</span> {r.runbookId}</>}
                  </div>
                </div>
              </div>
            ))}
          </Section>
        ))
      )}
    </div>
  );
}
