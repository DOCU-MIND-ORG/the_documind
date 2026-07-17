import { STREAM_EVENTS } from './streamConstants.js';
import * as handlers from './eventHandlers.js';

/**
 * Routes raw StreamEvents to their specific handlers.
 * Responsible for JSON parsing where applicable.
 * Returns an array of Action objects to be dispatched.
 */
export function dispatchStreamEvent(messageId, event) {
  const { type, rawData } = event;

  switch (type) {
    case STREAM_EVENTS.CITATIONS: {
      try {
        const json = JSON.parse(rawData);
        return handlers.handleCitations(messageId, json);
      } catch (e) {
        console.error('Failed to parse citations', e);
        return [];
      }
    }

    case STREAM_EVENTS.VISUALS: {
      try {
        const json = JSON.parse(rawData);
        return handlers.handleVisuals(messageId, json);
      } catch (e) {
        console.error('Failed to parse visuals', e);
        return [];
      }
    }

    case STREAM_EVENTS.PROGRESS: {
      try {
        const json = JSON.parse(rawData);
        return handlers.handleProgress(messageId, json);
      } catch (e) {
        console.error('Failed to parse progress', e);
        return [];
      }
    }

    case STREAM_EVENTS.WAITING: {
      try {
        const json = JSON.parse(rawData);
        return handlers.handleWaiting(messageId, json);
      } catch (e) {
        console.error('Failed to parse waiting event', e);
        return [];
      }
    }

    case STREAM_EVENTS.SCOPE_EXPANSION:
      return handlers.handleScopeExpansion(messageId);

    case STREAM_EVENTS.INGESTION_STATUS:
      return handlers.handleIngestionStatus(messageId, rawData);

    case STREAM_EVENTS.DONE:
      return handlers.handleDone(messageId);

    case STREAM_EVENTS.CANCELLED:
      return handlers.handleCancelled(messageId);

    case STREAM_EVENTS.RETRY:
      return handlers.handleRetry(messageId);

    case STREAM_EVENTS.MESSAGE:
    default:
      // Treat unknown types as standard text chunks
      return handlers.handleMessage(messageId, rawData);
  }
}
