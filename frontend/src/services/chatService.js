import { request } from './api.js';
import { streamManager } from './chat/streamManager.js';

const BASE_URL = import.meta.env.VITE_API_URL || '';

export const chatService = {
  /**
   * Submits a message to start a chat generation job on the backend.
   */
  submitMessage: async (sessionId, message, model, inflightJobIds = []) => {
    return request(`/api/chat/${sessionId}/message`, {
      method: 'POST',
      body: JSON.stringify({ message, model, inflightJobIds })
    });
  },

  /**
   * Gets the active generation state for a session, if any.
   */
  getActiveGeneration: async (sessionId) => {
    try {
      return await request(`/api/chat/${sessionId}/active-generation`, {
        hideProgress: true
      });
    } catch (e) {
      // 404 means no active generation, safely ignore
      return null;
    }
  },

  /**
   * Cancels an active stream and requests the backend to stop generation.
   */
  cancelGeneration: async (messageId) => {
    await streamManager.cancel(messageId);
    return true;
  },

  /**
   * Initiates the SSE connection to consume the stream.
   * Dispatches Redux actions back to the caller via onEvent.
   */
  startStream: (sessionId, messageId, onEvent, lastEventId = null) => {
    return streamManager.startStream(sessionId, messageId, onEvent, lastEventId);
  }
};
