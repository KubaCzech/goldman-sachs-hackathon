import React, { useEffect, useId, useState } from 'react';
import { dismiss, subscribe, toast } from '../toast.js';
import { copyText, pretty, sevColor } from '../util.js';

export function Toasts() {
  const [items, setItems] = useState([]);
  useEffect(() => subscribe(setItems), []);
  return (
    <div className="toasts" aria-live="polite">
      {items.map((t) => (
        <div key={t.id} className={`toast ${t.kind}`} onClick={() => dismiss(t.id)} role="status">
          {t.message}
        </div>
      ))}
    </div>
  );
}

export function Modal({ title, children, actions, onClose }) {
  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-label={title}>
        <h3>{title}</h3>
        <div className="modal-body">{children}</div>
        <div className="modal-actions">{actions}</div>
      </div>
    </div>
  );
}

export function Empty({ title, children }) {
  return (
    <div className="empty">
      {title && <div className="empty-title">{title}</div>}
      {children && <div>{children}</div>}
    </div>
  );
}

export function ErrorBox({ children }) {
  if (!children) return null;
  return <div className="error-box" role="alert">{children}</div>;
}

export function Field({ label, hint, children, className }) {
  return (
    <label className={`field ${className || ''}`}>
      <span className="field-label">{label}</span>
      {children}
      {hint && <span className="hint">{hint}</span>}
    </label>
  );
}

export function SeverityBadge({ severity, color }) {
  const sev = String(severity || 'unknown').toLowerCase();
  const bg = color || sevColor(sev);
  return (
    <span className="badge sev" style={{ background: bg, color: sev === 'warning' && !color ? '#3b2a00' : '#fff' }}>
      {sev}
    </span>
  );
}

export function StatusBadge({ status }) {
  const s = String(status || '').toUpperCase();
  const label = { OPEN: 'Open', ACKNOWLEDGED: 'Acknowledged', RESOLVED: 'Resolved' }[s] || status || '—';
  return <span className={`badge status status-${s.toLowerCase()}`}>{label}</span>;
}

export function CopyButton({ text, label = 'Copy' }) {
  const [done, setDone] = useState(false);
  const onClick = async () => {
    const ok = await copyText(text == null ? '' : String(text));
    if (ok) {
      setDone(true);
      setTimeout(() => setDone(false), 1500);
    } else toast.error('Could not copy. Select the text and copy it manually.');
  };
  return (
    <button type="button" className="btn small" onClick={onClick}>
      {done ? 'Copied' : label}
    </button>
  );
}

export function JsonBlock({ value }) {
  return <pre className="code">{pretty(value)}</pre>;
}

// Text input with suggestions of known field paths; free typing is allowed for fields not seen yet.
export function FieldInput({ value, onChange, fields, placeholder = 'Choose a field', ...rest }) {
  const id = useId();
  return (
    <>
      <input
        type="text"
        list={id}
        value={value || ''}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
        autoComplete="off"
        spellCheck={false}
        {...rest}
      />
      <datalist id={id}>
        {(fields || []).map((f) => (
          <option key={f.path} value={f.path}>
            {f.example != null && f.example !== '' ? `e.g. ${String(f.example).slice(0, 60)}` : f.type || ''}
          </option>
        ))}
      </datalist>
    </>
  );
}

// Editable list of field paths shown as removable chips.
export function PathList({ value, onChange, fields, emptyText, addLabel = 'Add field' }) {
  const [draft, setDraft] = useState('');
  const list = Array.isArray(value) ? value : [];
  const add = () => {
    const p = draft.trim();
    if (!p) return;
    if (!list.includes(p)) onChange([...list, p]);
    setDraft('');
  };
  return (
    <div className="pathlist">
      <div className="chips">
        {list.length === 0 && <span className="muted">{emptyText || 'None'}</span>}
        {list.map((p) => (
          <span className="chip" key={p}>
            {p}
            <button type="button" aria-label={`Remove ${p}`} onClick={() => onChange(list.filter((x) => x !== p))}>
              ×
            </button>
          </span>
        ))}
      </div>
      <div className="row">
        <FieldInput
          value={draft}
          onChange={setDraft}
          fields={(fields || []).filter((f) => !list.includes(f.path))}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              add();
            }
          }}
        />
        <button type="button" className="btn" onClick={add} disabled={!draft.trim()}>
          {addLabel}
        </button>
      </div>
    </div>
  );
}

export function Section({ title, hint, children, actions }) {
  return (
    <section className="card">
      <div className="card-head">
        <div>
          <h2>{title}</h2>
          {hint && <p className="muted">{hint}</p>}
        </div>
        {actions && <div className="row">{actions}</div>}
      </div>
      {children}
    </section>
  );
}

// Renders text, highlighting "⟦missing: path⟧" markers left by unresolved template placeholders.
export function TemplateText({ text }) {
  const s = text == null ? '' : typeof text === 'string' ? text : JSON.stringify(text);
  const parts = s.split(/(⟦[^⟧]*⟧)/g);
  return (
    <>
      {parts.map((p, i) =>
        p.startsWith('⟦') ? (
          <mark className="missing" key={i} title="This placeholder had no value in the event">
            {p}
          </mark>
        ) : (
          <React.Fragment key={i}>{p}</React.Fragment>
        )
      )}
    </>
  );
}
