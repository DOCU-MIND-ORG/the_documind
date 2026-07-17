
export const initialChatState = {
  sessionId: null,        
  messages: [],           
  hasMoreMessages: false,
  nextCursor: null,
  streamingMessage: null, // Separated streaming state to prevent array recreation on every token
  isStreaming: false,
  status: 'IDLE', // 'IDLE', 'STREAMING', 'CANCELLED', 'DONE', 'ERROR'
  messagesLoading: false,
  messagesFetched: false,
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
        messagesFetched: !!action.payload.messagesFetched,
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
      return { 
        ...state, 
        messages: action.payload.messages, 
        hasMoreMessages: action.payload.hasMore,
        nextCursor: action.payload.nextCursor,
        messagesLoading: false,
        messagesFetched: true
      };

    case 'PREPEND_MESSAGES':
      return {
        ...state,
        messages: [...action.payload.messages, ...state.messages],
        hasMoreMessages: action.payload.hasMore,
        nextCursor: action.payload.nextCursor,
        messagesLoading: false,
      };

    case 'MESSAGES_LOAD_FAILED':
      return { ...state, messagesLoading: false, messagesFetched: true };

    case 'SEND_MESSAGE_OPTIMISTIC': {
      const { userMessage, assistantPlaceholder } = action.payload;
      const newMessages = [...state.messages];
      if (userMessage) newMessages.push(userMessage);
      
      return {
        ...state,
        messages: newMessages,
        isStreaming: !!assistantPlaceholder,
        status: assistantPlaceholder ? 'STREAMING' : 'IDLE',
        streamingMessage: assistantPlaceholder ? { ...assistantPlaceholder, progressEvents: [] } : null,
        suggestedQuestions: [],
      };
    }

    case 'RECONNECT_STREAM': {
      const { assistantPlaceholder } = action.payload;
      const filteredMessages = state.messages.filter(m => m.id !== assistantPlaceholder.id);
      return {
        ...state,
        messages: filteredMessages,
        isStreaming: true,
        status: 'STREAMING',
        streamingMessage: { 
            ...assistantPlaceholder, 
            progressEvents: [],
            text: '', // Start empty, Redis will replay the stream from 0-0
            backendMessageId: assistantPlaceholder.id // During reconnect, the placeholder ID is the real backend ID
        },
      };
    }

    case 'APPEND_STREAM_CHUNK':
      if (state.streamingMessage && state.streamingMessage.id === action.payload.messageId) {
        return {
          ...state,
          streamingMessage: {
            ...state.streamingMessage,
            text: state.streamingMessage.text + action.payload.chunk
          }
        };
      }
      return state;

    case 'SET_BACKEND_MESSAGE_ID':
      if (state.streamingMessage && state.streamingMessage.id === action.payload.messageId) {
        return {
          ...state,
          streamingMessage: {
            ...state.streamingMessage,
            backendMessageId: action.payload.backendMessageId
          }
        };
      }
      return state;

    case 'RESET_STREAM_TEXT':
      if (state.streamingMessage && state.streamingMessage.id === action.payload.messageId) {
        return {
          ...state,
          streamingMessage: {
            ...state.streamingMessage,
            text: ''
          }
        };
      }
      return state;

    case 'UPDATE_PROGRESS':
      return {
        ...state,
        messages: state.messages.map(m => {
          if (m.id === action.payload.messageId) {
            return {
              ...m,
              progressEvents: [...(m.progressEvents || []), action.payload.progress]
            };
          }
          return m;
        }),
        streamingMessage: state.streamingMessage?.id === action.payload.messageId 
          ? { ...state.streamingMessage, progressEvents: [...(state.streamingMessage.progressEvents || []), action.payload.progress] } 
          : state.streamingMessage
      };

    case 'UPDATE_INGESTION_STATUS':
      return {
        ...state,
        messages: state.messages.map(m => {
          if (m.id === action.payload.messageId) {
            return { ...m, ingestionStatus: action.payload.status };
          }
          return m;
        }),
        streamingMessage: state.streamingMessage?.id === action.payload.messageId 
          ? { ...state.streamingMessage, ingestionStatus: action.payload.status } 
          : state.streamingMessage
      };

    case 'SET_CITATIONS':
      if (state.streamingMessage && state.streamingMessage.id === action.payload.messageId) {
        return {
          ...state,
          streamingMessage: {
            ...state.streamingMessage,
            citations: action.payload.citations
          }
        };
      }
      return state;

    case 'SET_VISUALS':
      if (state.streamingMessage && state.streamingMessage.id === action.payload.messageId) {
        return {
          ...state,
          streamingMessage: {
            ...state.streamingMessage,
            visuals: action.payload.visuals
          }
        };
      }
      return state;

    case 'STREAM_DONE':
      if (state.streamingMessage && state.streamingMessage.id === action.payload.messageId) {
        return {
          ...state,
          isStreaming: false,
          status: action.payload.finalStatus === 'CANCELLED' ? 'CANCELLED' : 'DONE',
          messages: [...state.messages, { 
            ...state.streamingMessage, 
            status: action.payload.finalStatus === 'CANCELLED' ? 'CANCELLED' : 'complete' 
          }],
          streamingMessage: null,
        };
      }
      return {
        ...state,
        isStreaming: false,
        status: 'DONE',
      };

    case 'STREAM_ERROR':
      if (state.streamingMessage && state.streamingMessage.id === action.payload.messageId) {
        return {
          ...state,
          isStreaming: false,
          status: 'ERROR',
          messages: [...state.messages, { ...state.streamingMessage, status: 'error' }],
          streamingMessage: null,
        };
      }
      return {
        ...state,
        isStreaming: false,
        status: 'ERROR',
      };

    case 'CANCEL_GENERATION':
      if (state.streamingMessage && state.streamingMessage.id === action.payload.messageId) {
        return {
          ...state,
          isStreaming: false,
          status: 'CANCELLED',
          messages: [...state.messages, { 
            ...state.streamingMessage, 
            text: state.streamingMessage.text ? state.streamingMessage.text : 'Generation stopped.',
            status: 'cancelled' 
          }],
          streamingMessage: null,
        };
      }
      return {
        ...state,
        isStreaming: false,
        status: 'CANCELLED',
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