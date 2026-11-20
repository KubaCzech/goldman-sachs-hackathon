import React, { useEffect, useState } from 'react';
import { api } from '../api.js';
import { toast } from '../toast.js';
import { CHANNELS, CHANNEL_LABELS, SEVERITIES, asArray } from '../util.js';
import { Empty, ErrorBox, Field, Section, SeverityBadge } from '../components/ui.jsx';

const FALLBACK_ZONES = ['UTC', 'Europe/London', 'Europe/Warsaw', 'Europe/Paris', 'America/New_York', 'America/Chicago', 'Asia/Singapore', 'Asia/Tokyo', 'Asia/Kolkata'];
function timezones() {
  try {
    const z = Intl.supportedValuesOf('timeZone');
    if (Array.isArray(z) && z.length) return z.includes('UTC') ? z : ['UTC', ...z];
  } catch {
    /* older browsers */
  }
  return FALLBACK_ZONES;
}
const ZONES = timezones();

function normalise(p) {
  const src = p && typeof p === 'object' ? p : {};
  const q = src.quietHours || {};
  return {
    email: src.email || '',
    phone: src.phone || '',
    channels: src.channels && typeof src.channels === 'object' ? src.channels : {},
    quietHours: {
      enabled: !!q.enabled,
      start: q.start || '20:00',
      end: q.end || '08:00',
      timezone: q.timezone || 'UTC',
      fallbackChannel: q.fallbackChannel || 'email',
    },
  };
}

export default function Preferences() {
  const [prefs, setPrefs] = useState(null);
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    api.get('/api/me/preferences').then((p) => setPrefs(normalise(p))).catch((e) => setError(e.message));
  }, []);

  if (error && !prefs) return <div><div className="page-head"><h1>My preferences</h1></div><ErrorBox>{error}</ErrorBox></div>;
  if (!prefs) return <Empty>Loading your preferences…</Empty>;

  const set = (patch) => setPrefs({ ...prefs, ...patch });
  const setQuiet = (patch) => setPrefs({ ...prefs, quietHours: { ...prefs.quietHours, ...patch } });
  const toggle = (sev, ch) => {
    const cur = asArray(prefs.channels[sev]);
    const next = cur.includes(ch) ? cur.filter((c) => c !== ch) : [...cur, ch];
    set({ channels: { ...prefs.channels, [sev]: next } });
  };

  const save = async () => {
    setSaving(true);
    try {
      const channels = { ...prefs.channels };
      [...SEVERITIES, 'unknown'].forEach((s) => { channels[s] = asArray(channels[s]); });
      const saved = await api.put('/api/me/preferences', { ...prefs, channels });
      if (saved && typeof saved === 'object' && (saved.channels || saved.quietHours)) setPrefs(normalise(saved));
      toast.success('Preferences saved');
    } catch (e) {
      toast.error(e);
    } finally {
      setSaving(false);
    }
  };

  const q = prefs.quietHours;
  const needsPhone = [...SEVERITIES, 'unknown'].some((s) => asArray(prefs.channels[s]).some((c) => c !== 'email')) && !prefs.phone;

  return (
    <div>
      <div className="page-head">
        <h1>My preferences</h1>
        <button type="button" className="btn primary" onClick={save} disabled={saving}>{saving ? 'Saving…' : 'Save preferences'}</button>
      </div>

      <Section title="Contact details">
        <div className="row wrap">
          <Field label="Email"><input type="email" value={prefs.email} onChange={(e) => set({ email: e.target.value })} placeholder="you@example.test" /></Field>
          <Field label="Phone number" hint="Used for SMS and phone calls."><input type="tel" value={prefs.phone} onChange={(e) => set({ phone: e.target.value })} placeholder="+44…" /></Field>
        </div>
      </Section>

      <Section title="How should we reach you?" hint="Tick the channels to use for each severity. A rule can override this when it names its own channels.">
        <table className="table matrix">
          <thead>
            <tr><th>Severity</th>{CHANNELS.map((c) => <th key={c}>{CHANNEL_LABELS[c]}</th>)}</tr>
          </thead>
          <tbody>
            {SEVERITIES.map((sev) => (
              <tr key={sev}>
                <th><SeverityBadge severity={sev} /></th>
                {CHANNELS.map((ch) => (
                  <td key={ch}>
                    <input type="checkbox" aria-label={`${sev} by ${CHANNEL_LABELS[ch]}`} checked={asArray(prefs.channels[sev]).includes(ch)} onChange={() => toggle(sev, ch)} />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
        {needsPhone && <div className="warn-box">Add a phone number above, otherwise SMS and phone notifications cannot reach you.</div>}
        <div className="hint">A severity with nothing ticked sends no notification; the alert still appears in the alert feed.</div>
      </Section>

      <Section title="Quiet hours" hint="During quiet hours every notification uses one fallback channel instead.">
        <label className="check">
          <input type="checkbox" checked={q.enabled} onChange={(e) => setQuiet({ enabled: e.target.checked })} /> Use quiet hours
        </label>
        <div className="row wrap">
          <Field label="From"><input type="time" value={q.start} disabled={!q.enabled} onChange={(e) => setQuiet({ start: e.target.value })} /></Field>
          <Field label="Until"><input type="time" value={q.end} disabled={!q.enabled} onChange={(e) => setQuiet({ end: e.target.value })} /></Field>
          <Field label="Time zone">
            <select value={q.timezone} disabled={!q.enabled} onChange={(e) => setQuiet({ timezone: e.target.value })}>
              {!ZONES.includes(q.timezone) && <option value={q.timezone}>{q.timezone}</option>}
              {ZONES.map((z) => <option key={z} value={z}>{z}</option>)}
            </select>
          </Field>
          <Field label="During quiet hours, use">
            <select value={q.fallbackChannel} disabled={!q.enabled} onChange={(e) => setQuiet({ fallbackChannel: e.target.value })}>
              {CHANNELS.map((c) => <option key={c} value={c}>{CHANNEL_LABELS[c]}</option>)}
            </select>
          </Field>
        </div>
      </Section>

      <button type="button" className="btn primary" onClick={save} disabled={saving}>{saving ? 'Saving…' : 'Save preferences'}</button>
    </div>
  );
}
