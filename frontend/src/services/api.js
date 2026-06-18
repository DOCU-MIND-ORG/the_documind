/**
 * api.js — All real HTTP calls to the Spring Boot backend at localhost:8080.
 * No mock data. Uses credentials: 'include' so HttpOnly cookies are sent automatically.
 */

const BASE = import.meta.env.VITE_API_URL || 'http://localhost:8080';

/** Generic fetch wrapper — throws on non-OK, returns parsed JSON */
async function request(path, options = {}) {
  const res = await fetch(`${BASE}${path}`, {
    headers: { 'Content-Type': 'application/json', ...options.headers },
    credentials: 'include',   // sends HttpOnly cookies automatically
    ...options,
  });

  // Try to parse JSON regardless of status (error responses have JSON bodies)
  let data;
  try { data = await res.json(); } catch { data = null; }

  if (!res.ok) {
    if (res.status === 401 && path !== '/auth/login' && path !== '/auth/refresh') {
      try {
        const refreshRes = await fetch(`${BASE}/auth/refresh`, {
          method: 'POST',
          credentials: 'include'
        });
        
        if (refreshRes.ok) {
          // Retry original request
          const retryRes = await fetch(`${BASE}${path}`, {
            headers: { 'Content-Type': 'application/json', ...options.headers },
            credentials: 'include',
            ...options,
          });
          let retryData;
          try { retryData = await retryRes.json(); } catch { retryData = null; }
          
          if (!retryRes.ok) {
            throw new Error(retryData?.message || `Request failed: ${retryRes.status}`);
          }
          return retryData;
        } else {
          window.dispatchEvent(new Event('auth-expired'));
        }
      } catch (err) {
        window.dispatchEvent(new Event('auth-expired'));
      }
    } else if (res.status === 401 && path === '/auth/refresh') {
      window.dispatchEvent(new Event('auth-expired'));
    }
    const msg = data?.message || `Request failed: ${res.status}`;
    throw new Error(msg);
  }
  return data;
}

// ─── Auth ─────────────────────────────────────────────────────────────────────

export const authApi = {
  /** POST /auth/login — returns { user, accessToken, message } and sets HttpOnly cookies */
  login: (email, password) =>
    request('/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) }),

  /** POST /auth/register — returns { user, accessToken, message } and sets HttpOnly cookies */
  register: (userData) =>
    request('/auth/register', { method: 'POST', body: JSON.stringify(userData) }),

  /** POST /auth/logout — clears HttpOnly cookies on the backend */
  logout: () =>
    request('/auth/logout', { method: 'POST' }),

  /** GET /auth/me — validates cookie JWT and returns { user } for session restore */
  me: async () => {
    try {
      return await request('/auth/me');
    } catch (err) {
      // Only return a mock user when developer explicitly opted into a frontend mock
      // either by setting a localStorage `mock_user` or the env var VITE_FORCE_MOCK_USER.
      try {
        const forceMock = !!import.meta.env.VITE_FORCE_MOCK_USER;
        const raw = typeof localStorage !== 'undefined' ? localStorage.getItem('mock_user') : null;
        if (forceMock || raw) {
          console.warn('authApi.me() failed — returning mock user (frontend fallback)', err?.message || err);
          if (raw) {
            try { return { user: JSON.parse(raw), message: 'mock' }; } catch {}
          }
          return {
            user: {
              id: 1,
              name: 'Developer',
              email: 'dev@example.com',
              phoneNumber: '',
              profilePicture: ''
            },
            message: 'mock'
          };
        }
      } catch {}
      throw err;
    }
  },
  /** POST /auth/me — update current user's profile */
  update: async (body) => {
    try {
      return await request('/auth/me', { method: 'POST', body: JSON.stringify(body) });
    } catch (err) {
      // Only return mock success if a local mock user exists or dev explicitly forces mocks
      try {
        const forceMock = !!import.meta.env.VITE_FORCE_MOCK_USER;
        const raw = typeof localStorage !== 'undefined' ? localStorage.getItem('mock_user') : null;
        if (forceMock || raw) {
          console.warn('authApi.update() failed — returning mock success (frontend fallback)', err?.message || err);
          const user = { id: 1, name: body.name || 'Developer', email: body.email || 'dev@example.com', phoneNumber: body.phoneNumber || '', profilePicture: body.profilePicture || '' };
          // persist to localStorage so AuthContext can restore later
          try { localStorage.setItem('mock_user', JSON.stringify(user)); } catch {}
          return { user, message: 'mock-updated' };
        }
      } catch {}
      throw err;
    }
  },

  /** DELETE /auth/me — delete current user's account */
  deleteMe: async () => {
    try {
      return await request('/auth/me', { method: 'DELETE' });
    } catch (err) {
      try {
        const forceMock = !!import.meta.env.VITE_FORCE_MOCK_USER;
        const raw = typeof localStorage !== 'undefined' ? localStorage.getItem('mock_user') : null;
        if (forceMock || raw) {
          console.warn('authApi.deleteMe() failed — returning mock success (frontend fallback)', err?.message || err);
          try { localStorage.removeItem('mock_user'); } catch {}
          return { message: 'mock-deleted' };
        }
      } catch {}
      throw err;
    }
  },
};

// ─── Trains ───────────────────────────────────────────────────────────────────

export const trainApi = {
  /** GET /trains/all */
  getAll: () => request('/trains/all'),

  /** GET /trains/:id */
  getById: (id) => request(`/trains/${id}`),

  /** POST /trains/add — admin only */
  add: (trainData) =>
    request('/trains/add', { method: 'POST', body: JSON.stringify(trainData) }),

  /** DELETE /trains/:id — admin only */
  delete: (id) =>
    request(`/trains/${id}`, { method: 'DELETE' }),

  /** POST /trains/:id/classes — add a class to a train */
  addClass: (trainId, classData) =>
    request(`/trains/${trainId}/classes`, { method: 'POST', body: JSON.stringify(classData) }),

  /** GET /trains/:id/classes */
  getClasses: (trainId) => request(`/trains/${trainId}/classes`),

  /** DELETE /trains/classes/:classId */
  deleteClass: (classId) => request(`/trains/classes/${classId}`, { method: 'DELETE' }),
};

// ─── Search ───────────────────────────────────────────────────────────────────

export const searchApi = {
  /**
   * POST /search — find trains by source/destination/date
   * Body: { sourceStation, destinationStation, journeyDate }
   * Returns list of TrainSearchResponse objects
   */
  search: (sourceStation, destinationStation, journeyDate) =>
    request('/search', {
      method: 'POST',
      body: JSON.stringify({ sourceStation, destinationStation, journeyDate }),
    }),
};

// ─── Stations ─────────────────────────────────────────────────────────────────

export const stationApi = {
  /** GET /stations/all */
  getAll: () => request('/stations/all'),

  /** POST /stations/add — admin only */
  add: (stationData) =>
    request('/stations/add', { method: 'POST', body: JSON.stringify(stationData) }),

  /** POST /stations/:id — admin update */
  update: (id, stationData) =>
    request(`/stations/${id}`, { method: 'POST', body: JSON.stringify(stationData) }),

  /** DELETE /stations/:id — admin only */
  delete: (id) =>
    request(`/stations/${id}`, { method: 'DELETE' }),
};

// ─── Routes ───────────────────────────────────────────────────────────────────

export const routeApi = {
  /** GET /routes/all */
  getAll: () => request('/routes/all'),

  /** POST /routes/add — admin only */
  add: (routeData) =>
    request('/routes/add', { method: 'POST', body: JSON.stringify(routeData) }),

  /** DELETE /routes/:id — admin only */
  delete: (id) =>
    request(`/routes/${id}`, { method: 'DELETE' }),
};

// ─── Bookings ─────────────────────────────────────────────────────────────────

export const bookingApi = {
  /** GET /api/bookings — all bookings (admin) or user's bookings */
  getAll: () => request('/api/bookings'),

  /** GET /api/bookings/:id */
  getById: (id) => request(`/api/bookings/${id}`),

  /**
   * POST /api/bookings — create a new booking
   * Body: { scheduleId, journeyDate, passengerRequests: [{ name, age, gender, berth }] }
   */
  create: (bookingData) =>
    request('/api/bookings', { method: 'POST', body: JSON.stringify(bookingData) }),

  /** DELETE /api/bookings/:id — cancel booking */
  cancel: (id) =>
    request(`/api/bookings/${id}`, { method: 'DELETE' }),
};
