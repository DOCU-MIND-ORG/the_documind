/**
 * Handlers receive parsed JSON data (or raw text) and return standard Redux Action objects.
 * They do not dispatch directly; they simply map backend events to UI actions.
 */

export function handleMessage(messageId, chunkText) {
  return [
    {
      type: 'APPEND_STREAM_CHUNK',
      payload: { messageId, chunk: chunkText }
    }
  ];
}

export function handleCitations(messageId, citationsJson) {
  return [
    {
      type: 'SET_CITATIONS',
      payload: { messageId, citations: citationsJson }
    }
  ];
}

export function handleVisuals(messageId, visualsJson) {
  return [
    {
      type: 'SET_VISUALS',
      payload: { messageId, visuals: visualsJson }
    }
  ];
}

export function handleProgress(messageId, progressData) {
  return [
    {
      type: 'UPDATE_PROGRESS',
      payload: { messageId, progress: progressData }
    }
  ];
}

export function handleWaiting(messageId, waitData) {
  if (waitData && waitData.statuses) {
    return [
      {
        type: 'UPDATE_INGESTION_STATUS',
        payload: { messageId, status: JSON.stringify(waitData.statuses) }
      }
    ];
  }
  return [];
}

export function handleIngestionStatus(messageId, rawData) {
  return [
    {
      type: 'UPDATE_INGESTION_STATUS',
      payload: { messageId, status: rawData }
    }
  ];
}

export function handleScopeExpansion(messageId) {
  return [
    {
      type: 'UPDATE_PROGRESS',
      payload: {
        messageId,
        progress: {
          id: crypto.randomUUID(),
          stage: 'RETRIEVAL',
          status: 'WARN',
          message: 'No sufficient evidence found'
        }
      }
    },
    {
      type: 'UPDATE_PROGRESS',
      payload: {
        messageId,
        progress: {
          id: crypto.randomUUID(),
          stage: 'RETRIEVAL',
          status: 'INFO',
          message: 'Expanding to entire knowledge base'
        }
      }
    }
  ];
}

export function handleDone(messageId) {
  return [
    {
      type: 'STREAM_DONE',
      payload: { messageId, finalStatus: 'COMPLETED' }
    }
  ];
}

export function handleCancelled(messageId) {
  return [
    {
      type: 'STREAM_DONE',
      payload: { messageId, finalStatus: 'CANCELLED' }
    }
  ];
}

export function handleRetry(messageId) {
  // Retry clears the text to start over
  return [
    {
      type: 'RESET_STREAM_TEXT',
      payload: { messageId }
    }
  ];
}

export function handleError(messageId, error = null) {
  return [
    {
      type: 'STREAM_ERROR',
      payload: { messageId, error }
    }
  ];
}
