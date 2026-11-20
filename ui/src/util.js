export const SEVERITIES = ['critical', 'error', 'warning', 'info'];
export const ALL_SEVERITIES = ['critical', 'error', 'warning', 'info', 'unknown'];
export const CHANNELS = ['email', 'sms', 'phone'];
export const CHANNEL_LABELS = { email: 'Email', sms: 'SMS', phone: 'Phone call' };
export const SEV_COLORS = {
  critical: '#b71c1c',
  error: '#d32f2f',
  warning: '#f9a825',
  info: '#1976d2',
  unknown: '#757575',
};

export const asArray = (v) => (Array.isArray(v) ? v : []);
export const sevColor = (sev) => SEV_COLORS[String(sev || 'unknown').toLowerCase()] || SEV_COLORS.unknown;
export const alertColor = (a) => (a && a.color) || sevColor(a && a.severity);

// First defined value among several possible key spellings (camelCase / snake_case).
export function pick(obj, ...keys) {
  if (!obj) return undefined;
  for (const k of keys) if (obj[k] !== undefined && obj[k] !== null) return obj[k];
  return undefined;
}

export function fmtTime(v) {
  if (v === undefined || v === null || v === '' || v === 0) return '—';
  const d = new Date(typeof v === 'string' && /^\d+$/.test(v) ? Number(v) : v);
  if (isNaN(d.getTime())) return String(v);
  return d.toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
}

export function fmtAgo(v) {
  if (!v) return '—';
  const t = new Date(v).getTime();
  if (isNaN(t)) return String(v);
  const s = Math.round((Date.now() - t) / 1000);
  if (s < 5) return 'just now';
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86400)}d ago`;
}

export function show(v) {
  if (v === undefined || v === null) return '';
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}

export function pretty(v) {
  if (v === undefined || v === null) return '';
  if (typeof v === 'string') {
    try {
      return JSON.stringify(JSON.parse(v), null, 2);
    } catch {
      return v;
    }
  }
  return JSON.stringify(v, null, 2);
}

// Flattens a JSON object to [{path, type, example}] leaf paths (arrays use index segments).
export function flattenPaths(obj, prefix = '', out = [], depth = 0) {
  if (obj === null || obj === undefined || depth > 6) return out;
  if (Array.isArray(obj)) {
    if (obj.length) flattenPaths(obj[0], prefix ? `${prefix}.0` : '0', out, depth + 1);
    return out;
  }
  if (typeof obj === 'object') {
    Object.keys(obj).forEach((k) => flattenPaths(obj[k], prefix ? `${prefix}.${k}` : k, out, depth + 1));
    return out;
  }
  if (prefix) out.push({ path: prefix, type: typeof obj, example: String(obj) });
  return out;
}

// Merge field lists, de-duplicated by path (first wins).
export function mergeFields(...lists) {
  const seen = new Set();
  const out = [];
  lists.forEach((l) =>
    asArray(l).forEach((f) => {
      if (f && f.path && !seen.has(f.path)) {
        seen.add(f.path);
        out.push(f);
      }
    })
  );
  return out;
}

export async function copyText(text) {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    /* fall through */
  }
  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(ta);
    return ok;
  } catch {
    return false;
  }
}

export function tryParseJson(text) {
  try {
    return { ok: true, value: JSON.parse(text) };
  } catch (e) {
    return { ok: false, error: e.message };
  }
}

export const sortAlerts = (list) => [...list].sort((a, b) => (b.lastSeen || 0) - (a.lastSeen || 0));
