
export const initialChatState = {
  sessionId: null,        
  messages: [],           
  streamingMessageId: null,
  isStreaming: false,
  messagesLoading: false,
  suggestedQuestions: [],
};

export function chatReducer(state, action) {
  if (action.sessionId !== undefined && action.sessionId !== state.sessionId) {
    return state;
  }

  switch (action.type) {
    case 'SET_SESSION':
      return {
        ...initialChatState,
        sessionId: action.payload.sessionId,
      };

    case 'SYNC_ROUTE_SESSION': {
      const paramId = action.payload.sessionId ? String(action.payload.sessionId) : null;
      const stateId = state.sessionId ? String(state.sessionId) : null;
      if (paramId === stateId) return state;
      
      return {
        ...initialChatState,
        sessionId: action.payload.sessionId,
      };
    }

    case 'MESSAGES_LOADING':
      return { ...state, messagesLoading: true };

    case 'MESSAGES_LOADED':
      return { ...state, messages: action.payload.messages, messagesLoading: false };

    case 'MESSAGES_LOAD_FAILED':
      return { ...state, messagesLoading: false };

    case 'SEND_MESSAGE_OPTIMISTIC': {
      const { userMessage, assistantPlaceholder } = action.payload;
      const newMessages = [...state.messages];
      if (userMessage) newMessages.push(userMessage);
      if (assistantPlaceholder) newMessages.push({ ...assistantPlaceholder, progressEvents: [] });
      
      return {
        ...state,
        messages: newMessages,
        isStreaming: true,
        streamingMessageId: assistantPlaceholder ? assistantPlaceholder.id : state.streamingMessageId,
        suggestedQuestions: [],
      };
    }

    case 'APPEND_STREAM_CHUNK':
      return {
        ...state,
        messages: state.messages.map(m =>
          m.id === action.payload.messageId
            ? { ...m, text: m.text + action.payload.chunk }
            : m
        ),
      };

    case 'RESET_STREAM_TEXT':
      // Sent when the backend discards a first-pass "couldn't find relevant
      // information" answer and retries retrieval with the adaptive method —
      // clears the stale text so the retried answer's tokens don't render
      // glued onto the end of the discarded one.
      return {
        ...state,
        messages: state.messages.map(m =>
          m.id === action.payload.messageId
            ? { ...m, text: '' }
            : m
        ),
      };

    case 'UPDATE_PROGRESS':
      return {
        ...state,
        messages: state.messages.map(m =>
          m.id === action.payload.messageId
            ? { ...m, progressEvents: [...(m.progressEvents || []), action.payload.progress] }
            : m
        ),
      };

    case 'SET_CITATIONS':
      return {
        ...state,
        messages: state.messages.map(m =>
          m.id === action.payload.messageId
            ? { ...m, citations: action.payload.citations }
            : m
        ),
      };

    case 'SET_VISUALS':
      return {
        ...state,
        messages: state.messages.map(m =>
          m.id === action.payload.messageId
            ? { ...m, visuals: action.payload.visuals }
            : m
        ),
      };

    case 'STREAM_DONE':
      return {
        ...state,
        isStreaming: false,
        streamingMessageId: null,
        messages: state.messages.map(m =>
          m.id === action.payload.messageId ? { ...m, status: 'complete' } : m
        ),
      };

    case 'STREAM_ERROR':
      return {
        ...state,
        isStreaming: false,
        streamingMessageId: null,
        messages: state.messages.map(m =>
          m.id === action.payload.messageId ? { ...m, status: 'error' } : m
        ),
      };

    case 'SET_SUGGESTED_QUESTIONS':
      return {
        ...state,
        suggestedQuestions: action.payload.questions,
      };

    case 'CLEAR_SUGGESTED_QUESTIONS':
      return {
        ...state,
        suggestedQuestions: [],
      };

    default:
      return state;
  }
}