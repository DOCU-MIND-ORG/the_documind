import { request } from './api.js';

export const preferenceService = {
  get: () => request('/api/preferences'),

  update: (preferences) =>
    request('/api/preferences', {
      method: 'PATCH',
      body: JSON.stringify(preferences),
    }),
};
