// chatReducer.js
//
// Single source of truth for: which session is active, what messages it has,
// and what (if anything) is currently streaming.
//
// Why a reducer instead of 4-5 separate useState calls:
//   - Every dispatched action carries the sessionId it belongs to. The reducer
//     drops any action whose sessionId doesn't match the *current* session,
//     which is what makes "switch chats while one is still streaming" safe.
//     With separate useState calls this is very easy to get wrong (a stray
//     setMessages from an old fetch can land on the new session's screen).
//   - Optimistic add + streaming append + rollback-on-error are all just
//     plain reducer cases, easy to unit test without touching React at all.

export const initialChatState = {
  sessionId: null,        // null = "new chat" / not-yet-created session
  messages: [],           // [{ id, role: 'USER' | 'ASSISTANT', text, status }]
  streamingMessageId: null,
  isStreaming: false,
  messagesLoading: false,
};

export function chatReducer(state, action) {
  // Guard: ignore actions tagged with a sessionId that no longer matches —
  // this is what prevents a slow network response from an old session
  // landing on whatever session the user has since navigated to.
  if (action.sessionId !== undefined && action.sessionId !== state.sessionId) {
    return state;
  }

  switch (action.type) {
    // Fired once we know which session we're viewing (existing or freshly created)
    case 'SET_SESSION':
      return {
        ...initialChatState,
        sessionId: action.payload.sessionId,
      };

    case 'SYNC_ROUTE_SESSION': {
      const paramId = action.payload.sessionId ? String(action.payload.sessionId) : null;
      const stateId = state.sessionId ? String(state.sessionId) : null;
      if (paramId === stateId) return state; // Already in sync, don't wipe!
      
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

    // Optimistic insert: user message + an empty assistant placeholder, both
    // rendered immediately, before any network call has resolved.
    case 'SEND_MESSAGE_OPTIMISTIC': {
      const { userMessage, assistantPlaceholder } = action.payload;
      return {
        ...state,
        messages: [...state.messages, userMessage, assistantPlaceholder],
        isStreaming: true,
        streamingMessageId: assistantPlaceholder.id,
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

    case 'STREAM_DONE':
      return {
        ...state,
        isStreaming: false,
        streamingMessageId: null,
        messages: state.messages.map(m =>
          m.id === action.payload.messageId ? { ...m, status: 'complete' } : m
        ),
      };

    // Roll back cleanly: mark the assistant message as failed instead of
    // leaving a permanently-empty bubble or silently dropping it.
    case 'STREAM_ERROR':
      return {
        ...state,
        isStreaming: false,
        streamingMessageId: null,
        messages: state.messages.map(m =>
          m.id === action.payload.messageId ? { ...m, status: 'error' } : m
        ),
      };

    default:
      return state;
  }
}