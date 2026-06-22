// This file contains functions for storing and showing the attachments (files) 
// uploaded by the user for a session

import { request } from './api.js';

const BASE_URL = import.meta.env.VITE_API_URL || '';

export const attachmentService = {
  upload: async (sessionId, file) => {
    const formData = new FormData();
    formData.append('file', file);

    const response = await fetch(`${BASE_URL}/api/sessions/${sessionId}/attachments/upload`, {
      method: 'POST',
      credentials: 'include',
      body: formData,
    });

    if (!response.ok) {
      const data = await response.json().catch(() => null);
      throw new Error(data?.message || `Upload failed: ${response.status}`);
    }

    return response.json();
  },

 
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

  getBySession: (sessionId) =>
    request(`/api/sessions/${sessionId}/attachments`),

  getSuggestedQuestions: (sessionId) =>
    request(`/api/sessions/${sessionId}/suggested-questions`),
};
