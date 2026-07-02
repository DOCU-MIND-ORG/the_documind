import { request } from './api.js';

export const preferenceService = {
  get: () => request('/api/preferences'),

  getModels: () => request('/api/preferences/models'),

  updateModel: (preferences) =>
    request('/api/preferences/model', {
      method: 'PUT',
      body: JSON.stringify(preferences),
    }),
};