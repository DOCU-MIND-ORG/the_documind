const BASE_URL = import.meta.env.VITE_API_URL || '';

export const chatService = {
  streamMessage: async (sessionId, message, onChunk, onError, onComplete) => {
    const streamUrl = `${BASE_URL}/api/chat/${sessionId}/stream`;
    try {
      const response = await fetch(streamUrl, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'text/event-stream',
        },
        credentials: 'include',
        body: JSON.stringify({ message }),
      });

      let finalResponse = response;
      if (!finalResponse.ok) {
        if (finalResponse.status === 401) {
          const refreshRes = await fetch(`${BASE_URL}/auth/refresh`, {
            method: 'POST',
            credentials: 'include'
          });
          if (refreshRes.ok) {
            finalResponse = await fetch(streamUrl, {
              method: 'POST',
              headers: {
                'Content-Type': 'application/json',
                'Accept': 'text/event-stream',
              },
              credentials: 'include',
              body: JSON.stringify({ message }),
            });
            if (!finalResponse.ok) {
              window.dispatchEvent(new Event('auth-expired'));
              throw new Error('UNAUTHORIZED');
            }
          } else {
            window.dispatchEvent(new Event('auth-expired'));
            throw new Error('UNAUTHORIZED');
          }
        } else {
          throw new Error(`HTTP error! status: ${finalResponse.status}`);
        }
      }

      const reader = finalResponse.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let buffer = '';

      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        
        buffer += decoder.decode(value, { stream: true });
        
        // SSE events are separated by double newline
        const parts = buffer.split('\n\n');
        
        // The last part is likely incomplete, keep it in the buffer
        buffer = parts.pop() || '';
        
        for (const part of parts) {
          const lines = part.split('\n');
          let eventData = [];
          for (const line of lines) {
            if (line.startsWith('data:')) {
              const dataStr = line.substring(5);
              const text = dataStr.startsWith(' ') ? dataStr.substring(1) : dataStr;
              eventData.push(text);
            }
          }
          if (eventData.length > 0) {
            onChunk(eventData.join('\n'));
          }
        }
      }
      
      // Flush any remaining data in buffer
      if (buffer.trim()) {
        const lines = buffer.split('\n');
        let eventData = [];
        for (const line of lines) {
          if (line.startsWith('data:')) {
            const dataStr = line.substring(5);
            const text = dataStr.startsWith(' ') ? dataStr.substring(1) : dataStr;
            eventData.push(text);
          }
        }
        if (eventData.length > 0) {
          onChunk(eventData.join('\n'));
        }
      }
      
      if (onComplete) onComplete();
    } catch (error) {
      console.error('Error in streamMessage:', error);
      if (onError) onError(error);
      throw error;
    }
  }
};
