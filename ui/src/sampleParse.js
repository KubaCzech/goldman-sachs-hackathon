import { useEffect, useState } from 'react';
import { api } from './api.js';

// Input formats a source can receive. "auto" detects the format of each message.
export const FORMATS = [
  ['auto', 'Detect automatically'],
  ['json', 'JSON (one object or an array)'],
  ['ndjson', 'JSON lines (one object per line)'],
  ['csv', 'CSV (header row, one event per row)'],
  ['tsv', 'TSV (tab separated)'],
  ['yaml', 'YAML'],
  ['xml', 'XML'],
  ['logfmt', 'key=value pairs (logfmt)'],
  ['syslog', 'Syslog lines'],
  ['form', 'Form fields (a=1&b=2)'],
  ['text', 'Plain text (one event per line)'],
];

export const FORMAT_LABELS = Object.fromEntries(FORMATS.map(([k, v]) => [k, v.split(' (')[0]]));

/**
 * Parses a pasted sample in any format with the same parser the stream uses.
 * Returns { ok, value (first event), format, count, error }.
 */
export async function parseSampleText(text, format) {
  if (!text || !text.trim()) return { ok: false, empty: true };
  try {
    const r = (await api.post('/api/formats/parse', { text, format: format || 'auto' })) || {};
    const events = Array.isArray(r.events) ? r.events : [];
    if (!events.length) return { ok: false, error: 'No event found in the sample.' };
    return { ok: true, value: events[0], format: r.format, count: r.count };
  } catch (e) {
    return { ok: false, error: e.message };
  }
}

/** Debounced version for text areas that change as the user types. */
export function useParsedSample(text, format) {
  const [state, setState] = useState({ ok: false, empty: true });
  useEffect(() => {
    if (!text || !text.trim()) {
      setState({ ok: false, empty: true });
      return undefined;
    }
    let live = true;
    const timer = setTimeout(async () => {
      const r = await parseSampleText(text, format);
      if (live) setState(r);
    }, 250);
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [text, format]);
  return state;
}

/** "Read as CSV · 3 events, the first is used" */
export function describeParsed(p) {
  if (!p || !p.ok) return '';
  const name = FORMAT_LABELS[p.format] || p.format;
  return p.count > 1 ? `Read as ${name} · ${p.count} events, the first one is used` : `Read as ${name}`;
}
