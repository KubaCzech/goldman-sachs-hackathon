// Tiny global toast store (no dependency).
let items = [];
let nextId = 1;
const listeners = new Set();
const emit = () => listeners.forEach((l) => l(items));

function push(kind, message, ms) {
  const id = nextId++;
  items = [...items, { id, kind, message: String(message || '') }];
  emit();
  setTimeout(() => dismiss(id), ms);
}

export function dismiss(id) {
  items = items.filter((t) => t.id !== id);
  emit();
}

export function subscribe(listener) {
  listeners.add(listener);
  listener(items);
  return () => listeners.delete(listener);
}

export const toast = {
  success: (m) => push('success', m, 3500),
  info: (m) => push('info', m, 3500),
  error: (m) => push('error', (m && m.message) || m || 'Something went wrong', 7000),
};
