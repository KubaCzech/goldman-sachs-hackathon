import React, { useMemo, useState } from 'react';
import { api } from '../api.js';
import { mergeFields } from '../util.js';
import { FieldInput, Help, Modal } from './ui.jsx';

// Filter value held by the parent: { mode: 'builder' | 'sql', conditions: [{field, op, value, value2, numeric}], sql }
export const emptyFilter = () => ({ mode: 'builder', conditions: [], sql: '' });

// Each field has a kind that decides which operators and which value input the builder offers.
// severity is an ordered enum (unknown < info < warning < error < critical on the server).
export const SEVERITY_VALUES = ['info', 'warning', 'error', 'critical', 'unknown'];
const ENUM_VALUES = { status: ['OPEN', 'ACKNOWLEDGED', 'RESOLVED'] };
const KNOWN_NUMERIC = new Set(['eventTime', 'count', 'firstSeen', 'lastSeen']);
const NUMBER_TYPES = ['number', 'integer', 'int', 'long', 'float', 'double', 'numeric', 'decimal'];
const NUMERIC = /^-?\d+(\.\d+)?$/;

const OPS_BY_KIND = {
  text: [['eq', 'is'], ['ne', 'is not'], ['in', 'is one of'], ['notin', 'is not one of'], ['contains', 'contains'], ['starts', 'starts with'], ['isnull', 'is missing'], ['notnull', 'is present']],
  number: [['eq', '='], ['ne', '≠'], ['lt', '<'], ['le', '≤'], ['gt', '>'], ['ge', '≥'], ['between', 'between'], ['in', 'is one of'], ['isnull', 'is missing'], ['notnull', 'is present']],
  severity: [['eq', 'is'], ['ne', 'is not'], ['in', 'is one of'], ['ge', 'is at least'], ['le', 'is at most'], ['gt', 'is above'], ['lt', 'is below'], ['between', 'is between']],
  enum: [['eq', 'is'], ['ne', 'is not'], ['in', 'is one of'], ['notin', 'is not one of']],
  boolean: [['eq', 'is'], ['ne', 'is not']],
};
// Labels for operators a stored filter may use on a field whose kind does not normally offer them.
const ANY_OP_LABEL = { eq: '=', ne: '≠', gt: '>', ge: '≥', lt: '<', le: '≤', between: 'between', in: 'is one of', notin: 'is not one of', contains: 'contains', starts: 'starts with', isnull: 'is missing', notnull: 'is present' };
const NO_VALUE = ['isnull', 'notnull'];
const LIST_OPS = ['in', 'notin'];

const findField = (path, fields) => (fields || []).find((x) => x.path === path);

// kind: 'text' | 'number' | 'severity' | 'enum' | 'boolean'. hint comes from a stored filter's literal type.
export function fieldKind(path, fields, hint) {
  const p = String(path || '').trim();
  if (p === 'severity') return 'severity';
  if (ENUM_VALUES[p]) return 'enum';
  const f = findField(p, fields);
  const t = String((f && f.type) || '').toLowerCase();
  if (NUMBER_TYPES.includes(t)) return 'number';
  if (t === 'boolean' || t === 'bool') return 'boolean';
  if (t === 'string') return 'text';
  if (KNOWN_NUMERIC.has(p)) return 'number';
  if (hint === 'number' || hint === 'boolean') return hint;
  const ex = f && f.example != null ? String(f.example).trim() : '';
  if (/^(true|false)$/i.test(ex)) return 'boolean';
  if (NUMERIC.test(ex)) return 'number';
  return 'text';
}

export const enumValues = (kind, path) => (kind === 'severity' ? SEVERITY_VALUES : kind === 'boolean' ? ['true', 'false'] : ENUM_VALUES[path] || []);

function opsFor(kind, currentOp) {
  const ops = OPS_BY_KIND[kind] || OPS_BY_KIND.text;
  if (currentOp && !ops.some(([o]) => o === currentOp)) return [...ops, [currentOp, ANY_OP_LABEL[currentOp] || currentOp]];
  return ops;
}

function coerce(raw, kind, numericHint) {
  const s = String(raw == null ? '' : raw).trim();
  if (kind === 'boolean' && /^(true|false)$/i.test(s)) return s.toLowerCase() === 'true';
  if (NUMERIC.test(s) && (kind === 'number' || numericHint)) return Number(s);
  return s;
}

const splitList = (v) => String(v == null ? '' : v).split(',').map((x) => x.trim()).filter((x) => x !== '');

const isComplete = (c) =>
  c && c.field && c.field.trim() &&
  (NO_VALUE.includes(c.op) ||
    (String(c.value ?? '').trim() !== '' && (c.op !== 'between' || String(c.value2 ?? '').trim() !== '')));

// Builder conditions -> AST (section 6.1). Always an AND list. Operators map to the API's AST nodes:
// ne, isnull/notnull, NOT IN = {op:not, arg:{op:in}}, contains / starts with = like '%x%' / 'x%'.
export function conditionsToAst(conditions, fields) {
  const args = (conditions || []).filter(isComplete).map((c) => {
    const field = c.field.trim();
    const kind = fieldKind(field, fields, c.hint);
    const val = (x) => coerce(x, kind, c.numeric);
    switch (c.op) {
      case 'isnull':
      case 'notnull':
        return { op: c.op, field };
      case 'in':
        return { op: 'in', field, values: splitList(c.value).map(val) };
      case 'notin':
        return { op: 'not', arg: { op: 'in', field, values: splitList(c.value).map(val) } };
      case 'between':
        return { op: 'between', field, low: val(c.value), high: val(c.value2) };
      case 'contains':
        return { op: 'like', field, value: `%${String(c.value).trim()}%` };
      case 'starts':
        return { op: 'like', field, value: `${String(c.value).trim()}%` };
      default:
        return { op: c.op, field, value: val(c.value) };
    }
  });
  return { op: 'and', args };
}

const hintOf = (v) => (typeof v === 'number' ? 'number' : typeof v === 'boolean' ? 'boolean' : undefined);
const scalar = (v) => v !== null && v !== undefined && typeof v !== 'object';

// AST -> builder conditions, or null when the builder cannot show the AST (section 6.4).
export function astToConditions(ast) {
  if (!ast || typeof ast !== 'object') return [];
  const nodes = ast.op === 'and' ? ast.args || [] : [ast];
  const out = [];
  const listCond = (n, op) => {
    const vals = n.values || [];
    if (vals.some((v) => !scalar(v) || String(v).includes(','))) return null;
    const numeric = vals.length > 0 && vals.every((v) => typeof v === 'number');
    return { field: n.field, op, value: vals.join(', '), value2: '', numeric, hint: vals.length ? hintOf(vals[0]) : undefined };
  };
  for (const n of nodes) {
    if (!n || typeof n !== 'object') return null;
    if (n.op === 'not') {
      const a = n.arg;
      if (!a || a.op !== 'in' || !a.field) return null;
      const c = listCond(a, 'notin');
      if (!c) return null;
      out.push(c);
      continue;
    }
    if (!n.field) return null;
    if (['eq', 'ne', 'gt', 'ge', 'lt', 'le'].includes(n.op)) {
      if (!scalar(n.value)) return null;
      out.push({ field: n.field, op: n.op, value: String(n.value), value2: '', numeric: typeof n.value === 'number', hint: hintOf(n.value) });
    } else if (n.op === 'in') {
      const c = listCond(n, 'in');
      if (!c) return null;
      out.push(c);
    } else if (n.op === 'between') {
      if (!scalar(n.low) || !scalar(n.high)) return null;
      out.push({ field: n.field, op: 'between', value: String(n.low), value2: String(n.high), numeric: typeof n.low === 'number' && typeof n.high === 'number', hint: hintOf(n.low) });
    } else if (n.op === 'like') {
      const p = typeof n.value === 'string' ? n.value : null;
      if (p && /^%[^%_]+%$/.test(p)) out.push({ field: n.field, op: 'contains', value: p.slice(1, -1), value2: '' });
      else if (p && /^[^%_]+%$/.test(p)) out.push({ field: n.field, op: 'starts', value: p.slice(0, -1), value2: '' });
      else return null;
    } else if (NO_VALUE.includes(n.op)) {
      out.push({ field: n.field, op: n.op, value: '', value2: '' });
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
    // The builder decides for itself what it can show (it supports more than the server's guiCompatible flag).
    const conds = r && r.ast ? astToConditions(r.ast) : null;
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

const ENUM_KINDS = ['severity', 'enum', 'boolean'];

function ValueInput({ c, kind, update, disabled }) {
  if (NO_VALUE.includes(c.op)) return null;
  const choices = enumValues(kind, c.field.trim());
  const listOp = LIST_OPS.includes(c.op);

  if (ENUM_KINDS.includes(kind) && choices.length) {
    if (listOp) {
      const picked = splitList(c.value);
      const toggle = (x) => {
        const next = picked.includes(x) ? picked.filter((p) => p !== x) : [...picked, x];
        update({ value: choices.filter((p) => next.includes(p)).join(', ') });
      };
      return (
        <div className="cond-values value-chips" role="group" aria-label="Values">
          {choices.map((x) => (
            <label key={x} className={`toggle ${picked.includes(x) ? 'on' : ''}`}>
              <input type="checkbox" checked={picked.includes(x)} onChange={() => toggle(x)} disabled={disabled} /> {x}
            </label>
          ))}
        </div>
      );
    }
    const select = (key, label) => (
      <select value={c[key] || ''} onChange={(e) => update({ [key]: e.target.value })} aria-label={label} disabled={disabled}>
        <option value="">Choose…</option>
        {choices.map((x) => <option key={x} value={x}>{x}</option>)}
        {c[key] && !choices.includes(c[key]) && <option value={c[key]}>{c[key]}</option>}
      </select>
    );
    if (c.op === 'between') return <div className="cond-values">{select('value', 'From')}<span className="muted">and</span>{select('value2', 'To')}</div>;
    return <div className="cond-values">{select('value', 'Value')}</div>;
  }

  const numeric = kind === 'number' && !listOp;
  const input = (key, label, placeholder) => (
    <input
      type={numeric ? 'number' : 'text'}
      className="cond-value"
      value={c[key] || ''}
      onChange={(e) => update({ [key]: e.target.value })}
      placeholder={placeholder}
      aria-label={label}
      disabled={disabled}
    />
  );
  if (c.op === 'between') return <div className="cond-values">{input('value', 'From', 'from')}<span className="muted">and</span>{input('value2', 'To', 'to')}</div>;
  if (listOp) return <div className="cond-values">{input('value', 'Values', kind === 'number' ? '1, 2, 3' : 'value1, value2, …')}</div>;
  return <div className="cond-values">{input('value', 'Value', kind === 'number' ? 'number' : 'value')}</div>;
}

export default function FilterEditor({ value, onChange, fields: rawFields, emptyText, disabled }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [dialog, setDialog] = useState(false);
  const fields = useMemo(() => mergeFields(rawFields), [rawFields]);
  const v = value || emptyFilter();
  const conditions = v.conditions || [];

  const setConditions = (next) => onChange({ ...v, conditions: next });
  const update = (i, patch) => setConditions(conditions.map((c, j) => (j === i ? { ...c, ...patch } : c)));

  // A new field may have another kind: keep the operator if it still applies, otherwise start again.
  const changeField = (i, field) => {
    const c = conditions[i];
    const before = fieldKind(c.field, fields, c.hint);
    const after = fieldKind(field, fields);
    if (before === after) return update(i, { field });
    const ops = OPS_BY_KIND[after];
    const keepOp = ops.some(([o]) => o === c.op);
    const keepValue = keepOp && !ENUM_KINDS.includes(after) && !ENUM_KINDS.includes(before);
    update(i, { field, op: keepOp ? c.op : ops[0][0], hint: undefined, numeric: false, ...(keepValue ? {} : { value: '', value2: '' }) });
  };

  const changeOp = (i, op) => {
    const c = conditions[i];
    const wasList = LIST_OPS.includes(c.op);
    const isList = LIST_OPS.includes(op);
    // Moving between a single value and a list keeps the first value.
    const patch = { op };
    if (wasList && !isList) patch.value = splitList(c.value)[0] || '';
    if (NO_VALUE.includes(op)) Object.assign(patch, { value: '', value2: '' });
    update(i, patch);
  };

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
      const conds = r && r.ast ? astToConditions(r.ast) : null;
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
        {v.mode === 'sql' && (
          <Help text="A WHERE clause: AND, OR, NOT, =, !=, >, >=, <, <=, IN (…), BETWEEN … AND …, LIKE, IS NULL. Text values go in 'single quotes'." />
        )}
        {busy && <span className="muted tab-note">Converting…</span>}
      </div>

      {v.mode === 'builder' ? (
        <div className="builder">
          {conditions.length === 0 && <div className="muted builder-empty">{emptyText || 'No conditions yet. Everything matches.'}</div>}
          {conditions.map((c, i) => {
            const kind = fieldKind(c.field, fields, c.hint);
            return (
              <div className="cond" key={i}>
                <span className="cond-join">{i === 0 ? 'Where' : 'and'}</span>
                <FieldInput className="cond-field" value={c.field} onChange={(field) => changeField(i, field)} fields={fields} aria-label="Field" disabled={disabled} />
                <select className="cond-op" value={c.op} onChange={(e) => changeOp(i, e.target.value)} aria-label="Operator" disabled={disabled}>
                  {opsFor(kind, c.op).map(([op, label]) => (
                    <option key={op} value={op}>{label}</option>
                  ))}
                </select>
                <ValueInput c={c} kind={kind} update={(patch) => update(i, patch)} disabled={disabled} />
                <button type="button" className="btn small ghost cond-remove" onClick={() => setConditions(conditions.filter((_, j) => j !== i))} aria-label="Remove condition" disabled={disabled}>
                  Remove
                </button>
              </div>
            );
          })}
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
            The builder only supports a list of conditions joined by AND. This filter uses something else, such as OR,
            NOT around a group, brackets, or a LIKE pattern other than "contains" or "starts with".
          </p>
          <p>You can keep editing it as SQL, or discard it and start again in the builder.</p>
        </Modal>
      )}
    </div>
  );
}
