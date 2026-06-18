import { request } from './api.js';

export const authService = {
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

  logout: () =>
    request('/auth/logout', {
      method: 'POST',
    }),

  me: () => request('/auth/me'),
};
