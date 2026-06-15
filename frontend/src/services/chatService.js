import axios from 'axios';

const API_URL = 'http://localhost:8080/chat';

export const chatService = {
  sendMessage: async (message) => {
    try {
      // Use the global request wrapper from api.js which handles auto-refresh
      const response = await fetch(`${API_URL}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ message })
      });
      if (!response.ok) {
        if (response.status === 401) {
           const refreshRes = await fetch(`${API_URL.replace('/chat', '')}/auth/refresh`, { method: 'POST', credentials: 'include' });
           if (refreshRes.ok) {
              const retryRes = await fetch(`${API_URL}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                credentials: 'include',
                body: JSON.stringify({ message })
              });
              if (!retryRes.ok) throw new Error("Retry failed");
              return await retryRes.json();
           } else {
              window.dispatchEvent(new Event('auth-expired'));
           }
        }
        throw new Error('Request failed');
      }
      return await response.json();
    } catch (error) {
      console.error('Error in chatService:', error);
      throw error;
    }
  },

  streamMessage: async (message, onChunk, onError, onComplete) => {
    try {
      const response = await fetch(`${API_URL}/stream`, {
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
          const refreshRes = await fetch(`${API_URL.replace('/chat', '')}/auth/refresh`, {
            method: 'POST',
            credentials: 'include'
          });
          if (refreshRes.ok) {
            finalResponse = await fetch(`${API_URL}/stream`, {
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
