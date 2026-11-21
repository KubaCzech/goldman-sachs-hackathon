import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { useBlocker } from 'react-router-dom';
import { Modal } from './components/ui.jsx';

// Unsaved-changes guard.
// Forms register with useUnsavedChanges(dirty, save); `save` runs the form's own save and resolves to true on success.
// While any registered form is dirty:
//  - route changes (nav links, Link, navigate, back/forward) are blocked by useBlocker and a dialog asks what to do;
//  - in-page changes (tabs, closing an editor) call guard(proceed) and get the same dialog;
//  - reloading or closing the browser tab shows the browser's own "leave site?" dialog (beforeunload).
const Ctx = createContext(null);

export function UnsavedChangesProvider({ children }) {
  const forms = useRef(new Set());
  const [prompt, setPrompt] = useState(null); // { proceed, cancel }
  const [saving, setSaving] = useState(false);

  const dirtyForms = useCallback(() => [...forms.current].filter((f) => f.current.dirty), []);

  const shouldBlock = useCallback(
    ({ currentLocation, nextLocation }) =>
      dirtyForms().length > 0 &&
      (currentLocation.pathname !== nextLocation.pathname || currentLocation.search !== nextLocation.search),
    [dirtyForms]
  );
  const blocker = useBlocker(shouldBlock);
  const blockerRef = useRef(blocker);
  blockerRef.current = blocker;

  useEffect(() => {
    if (blocker.state !== 'blocked') return;
    setPrompt({
      proceed: () => blockerRef.current.proceed && blockerRef.current.proceed(),
      cancel: () => blockerRef.current.reset && blockerRef.current.reset(),
    });
  }, [blocker.state]);

  useEffect(() => {
    const onBeforeUnload = (e) => {
      if (dirtyForms().length === 0) return undefined;
      e.preventDefault();
      e.returnValue = '';
      return '';
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, [dirtyForms]);

  const guard = useCallback(
    (proceed) => {
      if (dirtyForms().length === 0) return proceed();
      setPrompt({ proceed, cancel: () => {} });
      return undefined;
    },
    [dirtyForms]
  );

  const keepEditing = () => {
    if (prompt) prompt.cancel();
    setPrompt(null);
  };

  const discard = () => {
    const p = prompt;
    setPrompt(null);
    if (p) p.proceed();
  };

  const saveAndContinue = async () => {
    const p = prompt;
    setSaving(true);
    let ok = true;
    for (const f of dirtyForms()) {
      try {
        ok = f.current.save ? (await f.current.save()) === true : false;
      } catch {
        ok = false;
      }
      if (!ok) break;
    }
    setSaving(false);
    setPrompt(null);
    if (!p) return;
    if (ok) p.proceed();
    else p.cancel(); // the form's save has shown why it failed; stay on the page
  };

  const value = useMemo(() => ({ forms: forms.current, guard }), [guard]);

  return (
    <Ctx.Provider value={value}>
      {children}
      {prompt && (
        <Modal
          title="You have unsaved changes"
          onClose={saving ? undefined : keepEditing}
          actions={
            <>
              <button type="button" className="btn danger" onClick={discard} disabled={saving}>Discard changes</button>
              <button type="button" className="btn" onClick={saveAndContinue} disabled={saving}>{saving ? 'Saving…' : 'Save and continue'}</button>
              <button type="button" className="btn primary" autoFocus onClick={keepEditing} disabled={saving}>Keep editing</button>
            </>
          }
        >
          <p>If you leave now, your changes will be lost.</p>
        </Modal>
      )}
    </Ctx.Provider>
  );
}

const passThrough = (proceed) => proceed();

// Registers a form. Returns guard(proceed) for in-page navigation (tabs, closing an editor).
export function useUnsavedChanges(dirty, save) {
  const ctx = useContext(Ctx);
  const entry = useRef({ dirty: false, save: null });
  entry.current = { dirty: !!dirty, save };
  useEffect(() => {
    if (!ctx) return undefined;
    ctx.forms.add(entry);
    return () => ctx.forms.delete(entry);
  }, [ctx]);
  return ctx ? ctx.guard : passThrough;
}

// guard(proceed) without registering a form (e.g. for tabs that hold forms, or the user switcher).
export function useLeaveGuard() {
  const ctx = useContext(Ctx);
  return ctx ? ctx.guard : passThrough;
}
