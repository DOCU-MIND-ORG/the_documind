import { request } from './api.js';

export const folderService = {
  getAll: () =>
    request('/api/folders'),

  create: (data) =>
    request('/api/folders', {
      method: 'POST',
      body: JSON.stringify(data)
    }),

  update: (id, data) =>
    request(`/api/folders/${id}`, {
      method: 'PATCH',
      body: JSON.stringify(data)
    }),

  delete: (id) =>
    request(`/api/folders/${id}`, { method: 'DELETE' }),

  reorder: (requests) =>
    request('/api/folders/reorder', {
      method: 'PATCH',
      body: JSON.stringify(requests)
    })
};
