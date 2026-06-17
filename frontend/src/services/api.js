const BASE_URL = import.meta.env.VITE_API_URL || '';

async function request(path, options = {}) {
  const isFormData = options.body instanceof FormData;
  const headers = isFormData
    ? { ...options.headers }
    : { 'Content-Type': 'application/json', ...options.headers };

  const response = await fetch(`${BASE_URL}${path}`, {
    credentials: 'include',
    headers,
    ...options,
  });

  let data = null;
  try {
    data = await response.json();
  } catch {
    data = null;
  }

  if (!response.ok) {
    if (response.status === 401 && path !== '/auth/login' && path !== '/auth/refresh') {
      const refreshed = await refreshAccessToken();

      if (refreshed) {
        return request(path, options);
      }

      window.dispatchEvent(new Event('auth-expired'));
    }

    throw new Error(data?.message || `Request failed: ${response.status}`);
  }

  return data;
}

async function refreshAccessToken() {
  const response = await fetch(`${BASE_URL}/auth/refresh`, {
    method: 'POST',
    credentials: 'include',
  });

  return response.ok;
}

export const authApi = {
  login: (email, password) =>
    request('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    }),

  signup: (userData) =>
    request('/auth/signup', {
      method: 'POST',
      body: JSON.stringify(userData),
    }),

  register: (userData) =>
    request('/auth/signup', {
      method: 'POST',
      body: JSON.stringify(userData),
    }),

  logout: () =>
    request('/auth/logout', {
      method: 'POST',
    }),

  me: () => request('/auth/me'),
};

export const sessionApi = {
  getAll: () => request('/api/sessions'),

  getById: (sessionId) => request(`/api/sessions/${sessionId}`),

  create: (title) =>
    request('/api/sessions', {
      method: 'POST',
      body: JSON.stringify({ title }),
    }),

  delete: (sessionId) =>
    request(`/api/sessions/${sessionId}`, {
      method: 'DELETE',
    }),

  getMessages: (sessionId) => request(`/api/sessions/${sessionId}/messages`),

  uploadDocument: (sessionId, file) => {
    const formData = new FormData();
    formData.append('file', file);

    return request(`/api/sessions/${sessionId}/document`, {
      method: 'POST',
      body: formData,
    });
  },

  loadWikipedia: (sessionId, url) =>
    request(`/api/sessions/${sessionId}/wikipedia`, {
      method: 'POST',
      body: JSON.stringify({ url }),
    }),

  getSuggestions: (sessionId) => request(`/api/sessions/${sessionId}/suggestions`),
};
