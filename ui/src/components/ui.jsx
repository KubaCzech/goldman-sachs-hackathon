import React, { useCallback, useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { dismiss, subscribe, toast } from '../toast.js';
import { copyText, mergeFields, pretty, sevColor } from '../util.js';

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
      <span className="field-label">{label}{hint && <Help text={hint} />}</span>
      {children}
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

// Small "?" icon; the help text shows as a tooltip on hover or keyboard focus.
// A span (not a button) so it never becomes the labelled control when used inside a <label>.
// The tooltip is position: fixed and placed from the icon's position, so a scrolling or clipping
// ancestor (tables, panels) never cuts it off, and it is kept inside the viewport.
export function Help({ text, label = 'Help' }) {
  const id = useId();
  const iconRef = useRef(null);
  const tipRef = useRef(null);
  const [hover, setHover] = useState(false);
  const [focus, setFocus] = useState(false);
  const [pos, setPos] = useState(null);
  const open = hover || focus;

  useLayoutEffect(() => {
    if (!open) { setPos(null); return undefined; }
    const place = () => {
      const icon = iconRef.current;
      const tip = tipRef.current;
      if (!icon || !tip) return;
      const a = icon.getBoundingClientRect();
      const w = tip.offsetWidth;
      const h = tip.offsetHeight;
      const vw = document.documentElement.clientWidth;
      const vh = window.innerHeight;
      const center = a.left + a.width / 2;
      const left = Math.max(8, Math.min(center - w / 2, vw - 8 - w));
      let top = a.bottom + 7;
      let above = false;
      if (top + h > vh - 8 && a.top - 7 - h >= 8) { top = a.top - 7 - h; above = true; }
      setPos({ left, top, arrow: Math.max(8, Math.min(w - 8, center - left)), above });
    };
    place();
    window.addEventListener('scroll', place, true);
    window.addEventListener('resize', place);
    return () => {
      window.removeEventListener('scroll', place, true);
      window.removeEventListener('resize', place);
    };
  }, [open]);

  if (!text) return null;
  const style = pos
    ? { left: pos.left, top: pos.top, '--arrow-left': `${pos.arrow}px` }
    : { left: 0, top: 0, visibility: 'hidden' };
  return (
    <span className="help" onClick={(e) => e.preventDefault()} onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)}>
      <span
        className="help-icon"
        ref={iconRef}
        tabIndex={0}
        role="img"
        aria-label={label}
        aria-describedby={id}
        onFocus={() => setFocus(true)}
        onBlur={() => setFocus(false)}
      >
        ?
      </span>
      <span className={`help-tip ${open ? 'open' : ''} ${pos && pos.above ? 'above' : ''}`} role="tooltip" id={id} ref={tipRef} style={open ? style : undefined}>
        {text}
      </span>
    </span>
  );
}

// On/off switch. A button with role="switch", so Space and Enter toggle it and screen readers announce the state.
// label: accessible name; showLabel: also show it as text to the right. title: tooltip (also shown when disabled).
export function Toggle({ checked, onChange, label, showLabel = false, disabled = false, title }) {
  const button = (
    <button
      type="button"
      role="switch"
      aria-checked={!!checked}
      aria-label={label}
      className={`switch ${checked ? 'on' : ''}`}
      disabled={disabled}
      title={title}
      onClick={() => onChange && onChange(!checked)}
    >
      <span className="switch-knob" aria-hidden="true" />
    </button>
  );
  return (
    <span className={`switch-wrap ${disabled ? 'disabled' : ''}`} title={title}>
      {button}
      {showLabel && <span className="switch-label" aria-hidden="true" onClick={() => !disabled && onChange && onChange(!checked)}>{label}</span>}
    </span>
  );
}

const norm = (s) => String(s == null ? '' : s).toLowerCase().replace(/[_\s]+/g, ' ');

// Text input with a styled suggestion list. options: [{value, detail}].
// strict: only a value from the list can be chosen; anything else is reverted on blur.
export function Combobox({ value, onChange, options, strict, placeholder, disabled, onKeyDown, className, maxShown = 300, ...rest }) {
  const id = useId();
  const listId = `${id}-list`;
  const [text, setText] = useState(value || '');
  const [open, setOpen] = useState(false);
  const [typed, setTyped] = useState(false);
  const [active, setActive] = useState(-1);
  const listRef = useRef(null);
  const opts = useMemo(() => options || [], [options]);

  useEffect(() => setText(value || ''), [value]);

  const shown = useMemo(() => {
    if (!typed || !text) return opts.slice(0, maxShown);
    const q = norm(text);
    return opts.filter((o) => norm(o.value).includes(q)).slice(0, maxShown);
  }, [opts, text, typed, maxShown]);

  useEffect(() => {
    if (!open || active < 0 || !listRef.current) return;
    const el = listRef.current.children[active];
    if (el && el.scrollIntoView) el.scrollIntoView({ block: 'nearest' });
  }, [active, open]);

  const openList = () => {
    setOpen(true);
    setActive(Math.max(0, shown.findIndex((o) => o.value === value)));
  };

  const choose = (o) => {
    setText(o.value);
    setOpen(false);
    setTyped(false);
    onChange(o.value);
  };

  const commitOrRevert = () => {
    if (!strict) return;
    const exact = opts.find((o) => norm(o.value) === norm(text));
    if (exact) {
      setText(exact.value);
      if (exact.value !== value) onChange(exact.value);
    } else setText(value || '');
  };

  const keyDown = (e) => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      if (!open) return openList();
      const d = e.key === 'ArrowDown' ? 1 : -1;
      setActive((a) => Math.min(shown.length - 1, Math.max(0, a + d)));
      return;
    }
    if (e.key === 'Enter' && open && active >= 0 && shown[active]) {
      e.preventDefault();
      choose(shown[active]);
      return;
    }
    if (e.key === 'Escape' && open) {
      e.preventDefault();
      setOpen(false);
      return;
    }
    if (e.key === 'Enter') commitOrRevert();
    if (onKeyDown) onKeyDown(e);
  };

  return (
    <div className={`combo ${className || ''}`}>
      <input
        type="text"
        role="combobox"
        aria-autocomplete="list"
        aria-expanded={open}
        aria-controls={listId}
        aria-activedescendant={open && active >= 0 ? `${id}-o${active}` : undefined}
        value={text}
        placeholder={placeholder}
        disabled={disabled}
        autoComplete="off"
        spellCheck={false}
        onFocus={openList}
        onClick={() => !open && openList()}
        onBlur={() => {
          setOpen(false);
          setTyped(false);
          commitOrRevert();
        }}
        onChange={(e) => {
          setText(e.target.value);
          setTyped(true);
          setOpen(true);
          setActive(0);
          if (!strict) onChange(e.target.value);
        }}
        onKeyDown={keyDown}
        {...rest}
      />
      {open && !disabled && (
        <ul className="combo-list" role="listbox" id={listId} ref={listRef}>
          {shown.length === 0 && <li className="combo-empty">{strict ? 'No match. Choose a value from the list.' : 'No suggestions'}</li>}
          {shown.map((o, i) => (
            <li
              key={o.value}
              id={`${id}-o${i}`}
              role="option"
              aria-selected={o.value === value}
              className={`${i === active ? 'active' : ''} ${o.value === value ? 'selected' : ''}`}
              onMouseDown={(e) => {
                e.preventDefault();
                choose(o);
              }}
              onMouseEnter={() => setActive(i)}
            >
              <span className="combo-value">{o.value}</span>
              {o.detail && <span className="combo-detail">{o.detail}</span>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

const ORIGIN_LABEL = { 'built-in': 'built-in', schema: 'from schema', observed: 'seen in recent events', sample: 'from the sample' };
const fieldDetail = (f) => {
  const ex = f.example != null && f.example !== '' ? `e.g. ${String(f.example).slice(0, 40)}` : '';
  return [f.type, ex, ORIGIN_LABEL[f.origin]].filter(Boolean).join(' · ');
};

// Field path input with suggestions of known fields (one entry per path); free typing is allowed for fields not seen yet.
export function FieldInput({ value, onChange, fields, placeholder = 'Choose a field', ...rest }) {
  const options = useMemo(() => mergeFields(fields).map((f) => ({ value: f.path, detail: fieldDetail(f) })), [fields]);
  return <Combobox value={value} onChange={onChange} options={options} placeholder={placeholder} {...rest} />;
}

// Promise-based confirmation dialog in the app's modal style.
// const [confirm, confirmDialog] = useConfirm(); if (await confirm({ title, body, confirmLabel })) ...
export function useConfirm() {
  const [state, setState] = useState(null);
  const confirm = useCallback((opts) => new Promise((resolve) => setState({ ...opts, resolve })), []);
  const close = (result) => {
    if (state) state.resolve(result);
    setState(null);
  };
  const dialog = state ? (
    <Modal
      title={state.title}
      onClose={() => close(false)}
      actions={
        <>
          <button type="button" className="btn primary" autoFocus onClick={() => close(false)}>{state.cancelLabel || 'Cancel'}</button>
          <button type="button" className="btn danger" onClick={() => close(true)}>{state.confirmLabel || 'Delete'}</button>
        </>
      }
    >
      {typeof state.body === 'string' ? <p>{state.body}</p> : state.body}
    </Modal>
  ) : null;
  return [confirm, dialog];
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
          <h2>{title}{hint && <Help text={hint} />}</h2>
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
