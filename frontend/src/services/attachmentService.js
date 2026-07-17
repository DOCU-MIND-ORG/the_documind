// Service for attachment operations — uploads, listing by session, and global explore

import { request } from './api.js';

export const attachmentService = {
  upload: async (sessionId, file) => {
    const formData = new FormData();
    formData.append('file', file);

    return request(`/api/sessions/${sessionId}/attachments/upload`, {
      method: 'POST',
      body: formData,
    });
  },

  uploadWikipedia: async (sessionId, url) => {
    return request(`/api/sessions/${sessionId}/attachments/wikipedia`, {
      method: 'POST',
      body: JSON.stringify({ url }),
    });
  },

  searchWikipedia: async (sessionId, query) => {
    return request(`/api/sessions/${sessionId}/attachments/wikipedia/search?query=${encodeURIComponent(query)}`);
  },

  /**
   * Returns attachments uploaded in a specific session.
   * Backend reads from view_attachments joined to attachments, so only
   * files from THIS session are returned. Rows are deleted automatically
   * when the session is deleted.
   */
  getBySession: (sessionId) =>
    request(`/api/sessions/${sessionId}/attachments`),

  /**
   * Returns ALL attachments across every session (global knowledge base).
   * Used by the Explore page — includes PDFs, images, text files, Wikipedia
   * links, and any other uploaded type.
   */
  getAllGlobal: () =>
    request(`/api/explore/attachments`),

  /**
   * Deletes a file from the Explore page. If the caller is the only user who
   * ever uploaded this exact content, the backend purges it everywhere
   * (Cloudinary + document_chunks + Pinecone). If other users also uploaded
   * it, only the caller's own reference is removed. Returns
   * { attachmentId, fullyDeleted, ownerCount, message }.
   */
  deleteExploreAttachment: (attachmentId) =>
    request(`/api/explore/attachments/${attachmentId}`, { method: 'DELETE' }),

  getSuggestedQuestions: (sessionId) =>
    request(`/api/sessions/${sessionId}/suggested-questions`, { hideProgress: true }),

  triggerSuggestedQuestions: (sessionId) =>
    request(`/api/sessions/${sessionId}/suggested-questions`, { method: 'POST', hideProgress: true }),
};
