// Thin fetch wrapper: JWT in localStorage, Bearer header, 401 -> /login, {error} surfaced as ApiError.message.
const TOKEN_KEY = 'alerting.token';

export const getToken = () => localStorage.getItem(TOKEN_KEY);
export const setToken = (t) => (t ? localStorage.setItem(TOKEN_KEY, t) : localStorage.removeItem(TOKEN_KEY));

export class ApiError extends Error {
  constructor(message, status, data) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.data = data;
  }
}

function errorMessage(res, data) {
  let msg = null;
  if (data && typeof data === 'object') msg = data.error || data.message || data.title;
  else if (typeof data === 'string' && data.length < 300 && !data.trim().startsWith('<')) msg = data;
  if (!msg) {
    if (res.status === 403) msg = 'You are not allowed to do this.';
    else if (res.status === 404) msg = 'Not found.';
    else if (res.status >= 500) msg = `The server is unavailable or failed (${res.status}).`;
    else msg = `Request failed (${res.status}).`;
  }
  // The server's message already names the position for SQL errors; add it only when it is missing.
  if (data && typeof data === 'object' && data.position != null && !/at position/.test(msg)) msg += ` (at position ${data.position})`;
  return msg;
}

async function request(method, path, body, extraHeaders) {
  const headers = { Accept: 'application/json', ...(extraHeaders || {}) };
  const token = getToken();
  if (token && path.startsWith('/api')) headers.Authorization = `Bearer ${token}`;
  const init = { method, headers };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    init.body = typeof body === 'string' ? body : JSON.stringify(body);
  }
  let res;
  try {
    res = await fetch(path, init);
  } catch (e) {
    throw new ApiError('Cannot reach the server. Check that the backend is running.', 0, null);
  }
  const text = await res.text();
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }
  if (res.status === 401 && !path.startsWith('/api/auth/')) {
    setToken(null);
    if (window.location.pathname !== '/login') window.location.assign('/login');
    throw new ApiError('Your session has expired. Please log in again.', 401, data);
  }
  if (!res.ok) throw new ApiError(errorMessage(res, data), res.status, data);
  return data;
}

export const api = {
  get: (path) => request('GET', path),
  post: (path, body, headers) => request('POST', path, body === undefined ? {} : body, headers),
  put: (path, body) => request('PUT', path, body === undefined ? {} : body),
  del: (path) => request('DELETE', path),
};

// Builds "?a=1&b=2", skipping empty values.
export function qs(params) {
  const p = new URLSearchParams();
  Object.entries(params || {}).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') p.set(k, String(v));
  });
  const s = p.toString();
  return s ? `?${s}` : '';
}

export const streamUrl = (teamId) => `/api/alerts/stream${qs({ token: getToken(), teamId })}`;
