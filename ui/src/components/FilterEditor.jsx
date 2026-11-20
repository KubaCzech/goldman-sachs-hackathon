import React, { useState } from 'react';
import { api } from '../api.js';
import { ALL_SEVERITIES } from '../util.js';
import { FieldInput, Modal } from './ui.jsx';

// Filter value held by the parent: { mode: 'builder' | 'sql', conditions: [{field, op, value, value2, numeric}], sql }
export const emptyFilter = () => ({ mode: 'builder', conditions: [], sql: '' });

export const OPERATORS = [
  ['eq', 'equals'],
  ['in', 'is one of'],
  ['between', 'between'],
  ['gt', '>'],
  ['ge', '>='],
  ['lt', '<'],
  ['le', '<='],
];
const ORDERING = ['gt', 'ge', 'lt', 'le', 'between'];
const NUMERIC = /^-?\d+(\.\d+)?$/;

function coerce(raw, fieldType, op, numericHint) {
  const s = String(raw == null ? '' : raw).trim();
  if (fieldType === 'boolean' && /^(true|false)$/i.test(s)) return s.toLowerCase() === 'true';
  if (NUMERIC.test(s)) {
    if (fieldType === 'number' || numericHint) return Number(s);
    if (fieldType !== 'string' && ORDERING.includes(op)) return Number(s);
  }
  return s;
}

const isComplete = (c) =>
  c && c.field && c.field.trim() && String(c.value ?? '').trim() !== '' &&
  (c.op !== 'between' || String(c.value2 ?? '').trim() !== '');

// Builder conditions -> AST (section 6.1). Always an AND list, which is GUI-compatible by definition.
export function conditionsToAst(conditions, fields) {
  const typeOf = (path) => {
    const f = (fields || []).find((x) => x.path === path);
    return f ? f.type : undefined;
  };
  const args = (conditions || []).filter(isComplete).map((c) => {
    const field = c.field.trim();
    const t = typeOf(field);
    if (c.op === 'in') {
      const values = String(c.value).split(',').map((v) => v.trim()).filter((v) => v !== '')
        .map((v) => coerce(v, t, 'in', c.numeric));
      return { op: 'in', field, values };
    }
    if (c.op === 'between') {
      return { op: 'between', field, low: coerce(c.value, t, 'between', c.numeric), high: coerce(c.value2, t, 'between', c.numeric) };
    }
    return { op: c.op, field, value: coerce(c.value, t, c.op, c.numeric) };
  });
  return { op: 'and', args };
}

// AST -> builder conditions, or null when the AST is not GUI-compatible (section 6.4).
export function astToConditions(ast) {
  if (!ast || typeof ast !== 'object') return [];
  const nodes = ast.op === 'and' ? ast.args || [] : [ast];
  const out = [];
  for (const n of nodes) {
    if (!n || typeof n !== 'object' || !n.field) return null;
    if (['eq', 'gt', 'ge', 'lt', 'le'].includes(n.op)) {
      if (n.value === null || typeof n.value === 'object') return null;
      out.push({ field: n.field, op: n.op, value: String(n.value), value2: '', numeric: typeof n.value === 'number' });
    } else if (n.op === 'in') {
      const vals = n.values || [];
      if (vals.some((v) => v === null || typeof v === 'object' || String(v).includes(','))) return null;
      out.push({ field: n.field, op: 'in', value: vals.join(', '), value2: '', numeric: vals.length > 0 && vals.every((v) => typeof v === 'number') });
    } else if (n.op === 'between') {
      out.push({ field: n.field, op: 'between', value: String(n.low ?? ''), value2: String(n.high ?? ''), numeric: typeof n.low === 'number' && typeof n.high === 'number' });
    } else return null;
  }
  return out;
}

// Initial editor value from a stored AST and/or SQL text (rules, schemas).
export function filterFromStored(ast, sql) {
  const conds = ast ? astToConditions(ast) : sql && sql.trim() ? null : [];
  if (conds) return { mode: 'builder', conditions: conds, sql: sql || '' };
  return { mode: 'sql', conditions: [], sql: sql || '' };
}

// Initial editor value from SQL text only (saved filters): asks the API whether it fits the builder.
export async function filterFromSql(sql) {
  if (!sql || !sql.trim()) return emptyFilter();
  try {
    const r = await api.post('/api/filters/parse', { sql });
    const conds = r && r.guiCompatible !== false ? astToConditions(r.ast) : null;
    if (conds) return { mode: 'builder', conditions: conds, sql: (r && r.sql) || sql };
  } catch {
    /* keep SQL as typed; the error shows up when it is applied */
  }
  return { mode: 'sql', conditions: [], sql };
}

// Resolve the editor value to what the API needs. opts: { needSql, needAst }.
export async function resolveFilter(value, fields, opts = {}) {
  if (value.mode === 'builder') {
    const ast = conditionsToAst(value.conditions, fields);
    let sql;
    if (opts.needSql) {
      if (ast.args.length === 0) sql = '';
      else sql = ((await api.post('/api/filters/print', { ast })) || {}).sql || '';
    }
    return { ast, sql, empty: ast.args.length === 0 };
  }
  const sql = (value.sql || '').trim();
  let ast;
  if (opts.needAst) ast = sql ? ((await api.post('/api/filters/parse', { sql })) || {}).ast : { op: 'and', args: [] };
  return { ast, sql, empty: !sql };
}

export default function FilterEditor({ value, onChange, fields, emptyText, disabled }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [dialog, setDialog] = useState(false);
  const v = value || emptyFilter();
  const conditions = v.conditions || [];

  const setConditions = (next) => onChange({ ...v, conditions: next });
  const update = (i, patch) => setConditions(conditions.map((c, j) => (j === i ? { ...c, ...patch } : c)));

  const toSql = async () => {
    if (v.mode === 'sql' || busy) return;
    setError(null);
    const ast = conditionsToAst(conditions, fields);
    if (ast.args.length === 0) return onChange({ ...v, mode: 'sql', sql: '' });
    setBusy(true);
    try {
      const r = await api.post('/api/filters/print', { ast });
      onChange({ ...v, mode: 'sql', sql: (r && r.sql) || '' });
    } catch (e) {
      setError(`Could not convert to SQL: ${e.message}`);
    } finally {
      setBusy(false);
    }
  };

  const toBuilder = async () => {
    if (v.mode === 'builder' || busy) return;
    setError(null);
    const sql = (v.sql || '').trim();
    if (!sql) return onChange({ mode: 'builder', conditions: [], sql: '' });
    setBusy(true);
    try {
      const r = await api.post('/api/filters/parse', { sql });
      const conds = r && r.guiCompatible !== false ? astToConditions(r.ast) : null;
      if (conds) onChange({ mode: 'builder', conditions: conds, sql: (r && r.sql) || sql });
      else setDialog(true);
    } catch (e) {
      setError(`This SQL is not valid yet: ${e.message}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="filter-editor">
      <div className="tabs" role="tablist">
        <button type="button" role="tab" aria-selected={v.mode === 'builder'} className={`tab ${v.mode === 'builder' ? 'active' : ''}`} onClick={toBuilder} disabled={disabled}>
          Builder
        </button>
        <button type="button" role="tab" aria-selected={v.mode === 'sql'} className={`tab ${v.mode === 'sql' ? 'active' : ''}`} onClick={toSql} disabled={disabled}>
          SQL
        </button>
        {busy && <span className="muted tab-note">Converting…</span>}
      </div>

      {v.mode === 'builder' ? (
        <div className="builder">
          {conditions.length === 0 && <div className="muted builder-empty">{emptyText || 'No conditions yet. Everything matches.'}</div>}
          {conditions.map((c, i) => (
            <div className="cond" key={i}>
              <span className="cond-join">{i === 0 ? 'Where' : 'and'}</span>
              <FieldInput value={c.field} onChange={(field) => update(i, { field })} fields={fields} aria-label="Field" disabled={disabled} />
              <select value={c.op} onChange={(e) => update(i, { op: e.target.value })} aria-label="Operator" disabled={disabled}>
                {OPERATORS.map(([op, label]) => (
                  <option key={op} value={op}>{label}</option>
                ))}
              </select>
              {c.field === 'severity' && c.op !== 'in' && c.op !== 'between' ? (
                <select value={c.value} onChange={(e) => update(i, { value: e.target.value })} aria-label="Value" disabled={disabled}>
                  <option value="">Choose…</option>
                  {ALL_SEVERITIES.map((s) => <option key={s} value={s}>{s}</option>)}
                </select>
              ) : (
                <input
                  type="text"
                  value={c.value}
                  onChange={(e) => update(i, { value: e.target.value })}
                  placeholder={c.op === 'in' ? 'value1, value2, …' : c.op === 'between' ? 'from' : 'value'}
                  aria-label={c.op === 'between' ? 'From' : 'Value'}
                  disabled={disabled}
                />
              )}
              {c.op === 'between' && (
                <>
                  <span className="muted">and</span>
                  <input type="text" value={c.value2 || ''} onChange={(e) => update(i, { value2: e.target.value })} placeholder="to" aria-label="To" disabled={disabled} />
                </>
              )}
              <button type="button" className="btn small ghost" onClick={() => setConditions(conditions.filter((_, j) => j !== i))} aria-label="Remove condition" disabled={disabled}>
                Remove
              </button>
            </div>
          ))}
          <button type="button" className="btn small" disabled={disabled} onClick={() => setConditions([...conditions, { field: '', op: 'eq', value: '', value2: '' }])}>
            + Add condition
          </button>
          {conditions.some((c) => !isComplete(c)) && <div className="hint">Conditions without a field or value are ignored.</div>}
        </div>
      ) : (
        <div>
          <textarea
            className="mono"
            rows={3}
            value={v.sql || ''}
            onChange={(e) => onChange({ ...v, sql: e.target.value })}
            placeholder={"severity >= 'error' AND service = 'payments-api'"}
            spellCheck={false}
            disabled={disabled}
            aria-label="SQL filter"
          />
          <div className="hint">
            A WHERE clause: AND, OR, NOT, =, !=, &gt;, &gt;=, &lt;, &lt;=, IN (…), BETWEEN … AND …, LIKE, IS NULL. Text values go in 'single quotes'.
          </div>
        </div>
      )}

      {error && <div className="error-box">{error}</div>}

      {dialog && (
        <Modal
          title="This filter can't be shown in the builder"
          onClose={() => setDialog(false)}
          actions={
            <>
              <button type="button" className="btn primary" onClick={() => setDialog(false)}>Stay in SQL</button>
              <button type="button" className="btn danger" onClick={() => { setDialog(false); onChange({ mode: 'builder', conditions: [], sql: '' }); }}>
                Discard and go to builder
              </button>
            </>
          }
        >
          <p>
            The builder only supports a list of conditions joined by AND (equals, is one of, between, &gt;, &gt;=, &lt;, &lt;=).
            This filter uses something else, such as OR, NOT, LIKE or brackets.
          </p>
          <p>You can keep editing it as SQL, or discard it and start again in the builder.</p>
        </Modal>
      )}
    </div>
  );
}
