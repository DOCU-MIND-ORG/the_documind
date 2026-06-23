import { request } from './api.js';

const BASE_URL = import.meta.env.VITE_API_URL || '';

export const attachmentService = {
  /**
   * Upload a file for a given session.
   * Uses multipart/form-data — DO NOT set Content-Type manually (browser sets it with boundary).
   */
  upload: async (sessionId, file) => {
    const formData = new FormData();
    formData.append('file', file);

    const response = await fetch(`${BASE_URL}/api/sessions/${sessionId}/attachments/upload`, {
      method: 'POST',
      credentials: 'include',
      body: formData,
      // No Content-Type header — browser sets multipart/form-data + boundary automatically
    });

    if (!response.ok) {
      const data = await response.json().catch(() => null);
      throw new Error(data?.message || `Upload failed: ${response.status}`);
    }

    return response.json();
  },

  /**
   * Submit a Wikipedia URL for ingestion.
   */
  uploadWikipedia: async (sessionId, url) => {
    const response = await fetch(`${BASE_URL}/api/sessions/${sessionId}/attachments/wikipedia`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      credentials: 'include',
      body: JSON.stringify({ url }),
    });

    if (!response.ok) {
      const data = await response.json().catch(() => null);
      throw new Error(data?.message || `Wikipedia ingestion failed: ${response.status}`);
    }

    return response.json();
  },

  /**
   * Get all attachments for a session.
   */
  getBySession: (sessionId) =>
    request(`/api/sessions/${sessionId}/attachments`),
};
