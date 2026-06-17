import { request } from './api.js';

export const sessionService = {
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
};
