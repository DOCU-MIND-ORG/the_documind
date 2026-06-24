const BASE_URL = import.meta.env.VITE_API_URL || '';

// Parses one SSE block ("event: ...\ndata: ...") into { eventType, eventData }
function parseEventBlock(block) {
  let eventType = 'message';
  const eventData = [];
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) {
      eventType = line.substring(6).trim();
    } else if (line.startsWith('data:')) {
      const dataStr = line.substring(5);
      eventData.push(dataStr.startsWith(' ') ? dataStr.substring(1) : dataStr);
    }
  }
  return { eventType, eventData };
}

function dispatchEvent(block, onChunk, onCitations) {
  const { eventType, eventData } = parseEventBlock(block);
  if (eventData.length === 0) return;

  if (eventType === 'citations') {
    try {
      const citations = JSON.parse(eventData.join('\n'));
      if (onCitations) onCitations(citations);
    } catch (e) {
      console.error('Failed to parse citations', e);
    }
  } else {
    onChunk(eventData.join('\n'));
  }
}

export const chatService = {
  streamMessage: async (sessionId, message, model, onChunk, onCitations, onError, onComplete) => {
    const streamUrl = `${BASE_URL}/api/chat/${sessionId}/stream`;
    const buildRequest = () => fetch(streamUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream',
      },
      credentials: 'include',
      body: JSON.stringify({ message, model }),
    });

    try {
      let response = await buildRequest();

      if (!response.ok) {
        if (response.status !== 401) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }

        const refreshRes = await fetch(`${BASE_URL}/auth/refresh`, {
          method: 'POST',
          credentials: 'include',
        });

        if (!refreshRes.ok) {
          window.dispatchEvent(new Event('auth-expired'));
          throw new Error('UNAUTHORIZED');
        }

        response = await buildRequest();
        if (!response.ok) {
          window.dispatchEvent(new Event('auth-expired'));
          throw new Error('UNAUTHORIZED');
        }
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let buffer = '';

      while (true) {
        const { value, done } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const parts = buffer.split('\n\n');
        buffer = parts.pop() || '';

        for (const part of parts) {
          dispatchEvent(part, onChunk, onCitations);
        }
      }

      if (buffer.trim()) {
        dispatchEvent(buffer, onChunk, onCitations);
      }

      if (onComplete) onComplete();
    } catch (error) {
      console.error('Error in streamMessage:', error);
      if (onError) onError(error);
      throw error;
    }
  },
};
