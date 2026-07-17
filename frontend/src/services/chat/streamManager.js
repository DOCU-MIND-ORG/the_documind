import { authenticatedFetch } from './authenticatedFetch.js';
import { SSEParser } from './sseParser.js';
import { dispatchStreamEvent } from './eventDispatcher.js';
import { handleError } from './eventHandlers.js';

const activeStreams = new Map();

/**
 * Manages the lifecycle of SSE chat streams.
 */
export const streamManager = {
  /**
   * Starts or resumes a stream for a given messageId.
   */
  startStream: async (sessionId, messageId, onEvent, lastEventId = null) => {
    // Cleanup any existing stream for this message just in case
    streamManager.cleanup(messageId);

    const controller = new AbortController();
    
    const session = {
      messageId,
      controller,
      reader: null,
      lastEventId,
      status: 'RUNNING',
      createdAt: Date.now(),
      retryCount: 0
    };

    activeStreams.set(messageId, session);

    const streamUrl = `/api/chat/${sessionId}/stream/${messageId}`;
    const headers = {
      'Accept': 'text/event-stream',
      'ngrok-skip-browser-warning': 'true',
    };

    if (session.lastEventId) {
      headers['Last-Event-ID'] = session.lastEventId;
    }

    try {
      const response = await authenticatedFetch(streamUrl, {
        method: 'GET',
        headers,
        signal: controller.signal
      });

      const reader = response.body.getReader();
      session.reader = reader;

      const decoder = new TextDecoder('utf-8');
      const parser = new SSEParser();

      while (session.status === 'RUNNING') {
        const { value, done } = await reader.read();

        if (value) {
          const chunkString = decoder.decode(value, { stream: !done });
          const events = parser.parse(chunkString);

          for (const event of events) {
            if (event.id) {
              session.lastEventId = event.id;
            }
            
            const actions = dispatchStreamEvent(messageId, event);
            actions.forEach(action => onEvent(action));

            if (event.type === 'done' || event.type === 'cancelled') {
              session.status = 'CLOSED';
            }
          }
        }

        if (done) {
          // Flush any remaining buffer in the parser
          const finalEvents = parser.flush();
          for (const event of finalEvents) {
             const actions = dispatchStreamEvent(messageId, event);
             actions.forEach(action => onEvent(action));
          }
          break;
        }
      }
    } catch (error) {
      if (error.name === 'AbortError') {
        console.log(`Stream ${messageId} explicitly aborted`);
      } else {
        console.error(`Error in stream ${messageId}:`, error);
        const errorActions = handleError(messageId, error);
        errorActions.forEach(action => onEvent(action));
      }
    } finally {
      streamManager.cleanup(messageId);
    }
  },

  /**
   * Resumes a stream by establishing a new connection using the last recorded event ID.
   */
  resume: (sessionId, messageId, onEvent, lastEventId) => {
    return streamManager.startStream(sessionId, messageId, onEvent, lastEventId);
  },

  /**
   * Cancels an active stream and requests the backend to stop generation.
   */
  cancel: async (messageId) => {
    const session = activeStreams.get(messageId);
    if (session && session.status === 'RUNNING') {
      session.status = 'CLOSED';
      session.controller.abort();
    }
    
    // Also notify backend to cancel generation
    try {
      await authenticatedFetch(`/api/chat/generations/${messageId}`, {
        method: 'DELETE'
      });
    } catch (e) {
      console.error('Failed to notify backend of cancellation', e);
    }
    
    streamManager.cleanup(messageId);
  },

  /**
   * Cleans up references to the stream session.
   */
  cleanup: (messageId) => {
    const session = activeStreams.get(messageId);
    if (session) {
      if (session.status === 'RUNNING') {
        session.controller.abort();
      }
      activeStreams.delete(messageId);
    }
  }
};
