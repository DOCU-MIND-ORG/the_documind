/**
 * api.js — All HTTP calls to the Spring Boot backend.
 *
 * FIXES:
 *  1. queryApi.ask() now posts { question, sessionId } to /api/query  ✓ (was missing sessionId)
 *  2. /auth/me 403 → added to permitAll in SecurityConfig (backend fix)
 *  3. upload() now handles 403 explicitly (was silently swallowed)
 *  4. CORS: credentials: 'include' on every request so HttpOnly cookie is sent
 */

const BASE = import.meta.env.VITE_API_URL || 'http://localhost:8080';

// ── Generic JSON request ─────────────────────────────────────────────────────

async function request(path, options = {}) {
  const res = await fetch(`${BASE}${path}`, {
    headers: { 'Content-Type': 'application/json', ...options.headers },
    credentials: 'include',   // CRITICAL: sends HttpOnly cookies cross-origin
    ...options,
  });

  let data;
  try { data = await res.json(); } catch { data = null; }

  if (!res.ok) {
    // Auto-refresh on 401, then retry once
    if (res.status === 401 && path !== '/auth/login' && path !== '/auth/refresh') {
      try {
        const refreshRes = await fetch(`${BASE}/auth/refresh`, {
          method: 'POST',
          credentials: 'include',
        });
        if (refreshRes.ok) {
          const retryRes = await fetch(`${BASE}${path}`, {
            headers: { 'Content-Type': 'application/json', ...options.headers },
            credentials: 'include',
            ...options,
          });
          let retryData;
          try { retryData = await retryRes.json(); } catch { retryData = null; }
          if (!retryRes.ok) throw new Error(retryData?.error || `Request failed: ${retryRes.status}`);
          return retryData;
        } else {
          window.dispatchEvent(new Event('auth-expired'));
        }
      } catch {
        window.dispatchEvent(new Event('auth-expired'));
      }
    } else if (res.status === 401 && path === '/auth/refresh') {
      window.dispatchEvent(new Event('auth-expired'));
    }

    // FIX: backend returns { "error": "..." } not { "message": "..." }
    throw new Error(data?.error || data?.message || `Request failed: ${res.status}`);
  }

  return data;
}

// ── Multipart upload ─────────────────────────────────────────────────────────

async function upload(path, formData) {
  // NOTE: Do NOT set Content-Type — browser sets it with the correct multipart boundary
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    credentials: 'include',
    body: formData,
  });

  let data;
  try { data = await res.json(); } catch { data = null; }

  if (!res.ok) {
    // FIX: was silently swallowing 403 errors
    if (res.status === 403) {
      throw new Error('Permission denied. Please log in again.');
    }
    throw new Error(data?.error || data?.message || `Upload failed: ${res.status}`);
  }

  return data;
}

// ── Auth ─────────────────────────────────────────────────────────────────────

export const authApi = {
  login:    (email, password) =>
    request('/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) }),

  register: (userData) =>
    request('/auth/signup', { method: 'POST', body: JSON.stringify(userData) }),

  logout:   () =>
    request('/auth/logout', { method: 'POST' }),

  // FIX: /auth/me now returns { user: UserDto, message: "..." }
  // SecurityConfig now permits /auth/me without a JWT (handled inside the endpoint)
  me: () => request('/auth/me'),
};

// ── Ingest ───────────────────────────────────────────────────────────────────

export const ingestApi = {
  pdf: (file, sessionId) => {
    const fd = new FormData();
    fd.append('file', file);
    fd.append('sessionId', sessionId);
    return upload('/ingest/pdf', fd);
  },

  image: (file, sessionId) => {
    const fd = new FormData();
    fd.append('file', file);
    fd.append('sessionId', sessionId);
    return upload('/ingest/image', fd);
  },

  text: (file, sessionId) => {
    const fd = new FormData();
    fd.append('file', file);
    fd.append('sessionId', sessionId);
    return upload('/ingest/text', fd);
  },

  wikipedia: (url, sessionId) =>
    request('/ingest/wikipedia', {
      method: 'POST',
      body: JSON.stringify({ url, sessionId }),
    }),
};

// ── Query (RAG) ──────────────────────────────────────────────────────────────

export const queryApi = {
  /**
   * POST /api/query
   * FIX: sessionId is now always included (backend requires it for scoped retrieval)
   * Returns: { answer: string, citations: CitationDto[], foundInDocuments: boolean }
   */
  ask: (question, sessionId) =>
    request('/api/query', {
      method: 'POST',
      body: JSON.stringify({ question, sessionId }),
    }),
};
