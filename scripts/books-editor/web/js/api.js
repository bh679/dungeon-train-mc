/**
 * api.js — the editor's thin client for serve.py. Every call goes to the local server, which is the
 * only thing that touches the working tree.
 */

async function request(method, url, body) {
  const res = await fetch(url, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
    cache: 'no-store',
  });
  const payload = await res.json().catch(() => ({ error: `${res.status} ${res.statusText}` }));
  if (!res.ok) {
    const err = new Error(payload.error || `${res.status} ${res.statusText}`);
    err.status = res.status;
    throw err;
  }
  return payload;
}

const q = (params) => new URLSearchParams(params).toString();

export const listCorpus = () => request('GET', '/api/corpus');
export const readBook = (path) => request('GET', `/api/book?${q({ path })}`);
export const readLocale = (path, locale) => request('GET', `/api/locale?${q({ path, locale })}`);
export const saveBook = (path, data, style, mtime) =>
  request('PUT', `/api/book?${q({ path })}`, { data, style, mtime });
export const createBook = (path, data) => request('POST', '/api/create', { path, data });
export const moveBook = (from, to) => request('POST', '/api/move', { from, to });
