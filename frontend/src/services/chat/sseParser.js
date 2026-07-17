export class SSEParser {
  constructor() {
    this.buffer = '';
  }

  /**
   * Parses an incoming chunk of UTF-8 string data into SSE events.
   * @param {string} chunk 
   * @returns {Array<{type: string, rawData: string, id: string | null}>}
   */
  parse(chunk) {
    this.buffer += chunk;
    const parts = this.buffer.split('\n\n');
    
    // The last part might be incomplete, keep it in the buffer
    this.buffer = parts.pop() || '';

    const events = [];

    for (const part of parts) {
      const parsedEvent = this.parseEvent(part);
      if (parsedEvent) {
        events.push(parsedEvent);
      }
    }

    return events;
  }

  /**
   * Flushes any remaining buffer into an event (useful when stream abruptly ends).
   */
  flush() {
    if (this.buffer.trim()) {
      const parsedEvent = this.parseEvent(this.buffer);
      this.buffer = '';
      if (parsedEvent) {
        return [parsedEvent];
      }
    }
    return [];
  }

  parseEvent(eventString) {
    const lines = eventString.split('\n');
    let type = 'message'; // default SSE type
    let data = [];
    let id = null;

    for (const line of lines) {
      if (line.startsWith('event:')) {
        type = line.substring(6).trim();
      } else if (line.startsWith('data:')) {
        data.push(line.substring(5));
      } else if (line.startsWith('id:')) {
        id = line.substring(3).trim();
      }
    }

    if (data.length > 0 || type === 'done' || type === 'cancelled' || type === 'retry') {
      return {
        type,
        rawData: data.join('\n'),
        id
      };
    }
    
    return null;
  }
}
