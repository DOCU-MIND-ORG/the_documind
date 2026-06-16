/**
 * chatService.js — streaming chat via SSE + non-streaming fallback.
 * FIXES:
 *  - Removed unused `axios` import
 *  - Added sessionId to every request body (required by RagChatService)
 *  - streamMessage now returns { text, citations } properly
 *  - SSE parsing handles data: [DONE] sentinel from Spring AI
 */

const BASE = import.meta.env.VITE_API_URL || 'http://localhost:8080';

export const chatService = {
  /**
   * Non-streaming chat — returns full answer + citations at once.
   */
  sendMessage: async (message, sessionId) => {
    const response = await fetch(`${BASE}/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({message, sessionId}),
    });

    if (!response.ok) {
      const err = await response.json().catch(() => ({}));
      throw new Error(err?.error || `Request failed: ${response.status}`);
    }

    return await response.json();
    // Returns: { answer, citations, foundInDocuments }
  },

  /**
   * Streaming chat via SSE — streams tokens from /chat/stream.
   * NOTE: /chat/stream uses the simple GeminiChatService (no RAG context).
   * For RAG answers with citations, use sendMessage() instead.
   *
   * @param {string} message
   * @param {string} sessionId
   * @param {(chunk: string) => void} onChunk     called on each token
   * @param {(error: Error) => void}  onError     called on failure
   * @param {() => void}              onComplete  called when stream ends
   */
  streamMessage: async (message, sessionId, onChunk, onError, onComplete) => {
    try {
      const response = await fetch(`${BASE}/chat/stream`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'text/event-stream',
        },
        credentials: 'include',
        // FIX: added sessionId — backend needs it
        body: JSON.stringify({ message, sessionId }),
      });

      // Handle 401 → refresh → retry
      let finalResponse = response;
      if (!finalResponse.ok) {
        if (finalResponse.status === 401) {
          const refreshRes = await fetch(`${BASE}/auth/refresh`, {
            method: 'POST',
            credentials: 'include',
          });
          if (refreshRes.ok) {
            finalResponse = await fetch(`${BASE}/chat/stream`, {
              method: 'POST',
              headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
              credentials: 'include',
              body: JSON.stringify({ message, sessionId }),
            });
          } else {
            window.dispatchEvent(new Event('auth-expired'));
            throw new Error('UNAUTHORIZED');
          }
        } else {
          const err = await finalResponse.json().catch(() => ({}));
          throw new Error(err?.error || `HTTP error: ${finalResponse.status}`);
        }
      }

      const reader = finalResponse.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let buffer = '';

      while (true) {
        const { value, done } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const parts = buffer.split('\n\n');
        buffer = parts.pop() || '';

        for (const part of parts) {
          for (const line of part.split('\n')) {
            if (!line.startsWith('data:')) continue;
            const text = line.slice(5).trimStart();
            // Spring AI sends "data: [DONE]" at end of stream
            if (text === '[DONE]') continue;
            if (text) onChunk(text);
          }
        }
      }

      // Flush remaining buffer
      if (buffer.trim()) {
        for (const line of buffer.split('\n')) {
          if (!line.startsWith('data:')) continue;
          const text = line.slice(5).trimStart();
          if (text && text !== '[DONE]') onChunk(text);
        }
      }

      if (onComplete) onComplete();
    } catch (error) {
      console.error('streamMessage error:', error);
      if (onError) onError(error);
    }
  },
};
