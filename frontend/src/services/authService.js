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

  update: (userData) =>
    request('/auth/update', {
      method: 'PUT',
      body: JSON.stringify(userData),
    }),

  updateProfileImage: (file) => {
    const formData = new FormData();
    formData.append('file', file);
    return request('/auth/update-profile-image', {
      method: 'PUT',
      body: formData,
    });
  },

  deleteMe: () =>
    request('/auth/me', {
      method: 'DELETE',
    }),
};
