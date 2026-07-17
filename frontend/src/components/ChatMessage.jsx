import React, { memo } from 'react';
import ThinkingProcess from './ThinkingProcess.jsx';
import ImageDeck from './ImageDeck.jsx';
import Streaming from './streaming.jsx';

const ChatMessage = memo(function ChatMessage({ msg, isStreaming, setActiveCitation, showSuggestBulb, onSuggestQuestions, isGeneratingQuestions, isProcessingUpload, ingestionStatus, pendingFiles }) {
  const isBot = msg.role === 'ASSISTANT';

  const extractedImages = [];
  if (msg.text) {
    const mdImageRegex = /!\[([^\]]*)\]\(([^)]+)\)/g;
    let match;
    while ((match = mdImageRegex.exec(msg.text)) !== null) {
      const url = match[2];
      if (url.startsWith('http') || url.startsWith('data:') || url.startsWith('/') || url.startsWith('file-proxy')) {
        extractedImages.push({
          semanticId: url,
          imageUrl: url,
          caption: match[1] || 'Extracted image',
        });
      }
    }
  }
  
  const allVisuals = [...(msg.visuals || []), ...extractedImages].reduce((acc, curr) => {
    if (curr.imageUrl && !acc.some(v => v.imageUrl === curr.imageUrl)) {
      acc.push(curr);
    }
    return acc;
  }, []);

  return (
    <div className={`flex items-start gap-2.5 ${isBot ? '' : 'flex-row-reverse'}`}>
      <div className={`flex flex-col gap-1 max-w-[85%] ${isBot ? '' : 'items-end'}`}>
        <div className="flex flex-col gap-1.5 w-full">
          <div className={`px-4 py-3 rounded-2xl text-[13px] leading-relaxed ${isBot
            ? 'text-primary'
            : 'bg-blue-600 text-white rounded-tr-sm'
          } ${msg.status === 'error' ? 'border border-red-500/40' : ''}`}
          style={isBot ? {} : {}}>
          
          {isBot && msg.progressEvents && msg.progressEvents.length > 0 && (
            <ThinkingProcess
              events={msg.progressEvents}
              isComplete={msg.status !== 'streaming' || msg.text.length > 0}
            />
          )}
          
          {isBot && allVisuals.length > 0 && (
            <ImageDeck images={allVisuals.slice(0, 5)} maxStack={5} />
          )}
          
          {isBot
            ? <Streaming
              text={msg.text}
              isStreaming={isStreaming}
              citations={msg.citations}
              visuals={msg.visuals}
              onCitationClick={setActiveCitation}
              isProcessingUpload={isProcessingUpload}
              ingestionStatus={ingestionStatus}
              pendingFiles={pendingFiles}
            />
            : <p className="whitespace-pre-wrap">{msg.text}</p>
          }
          
          {( (msg.citations && msg.citations.length > 0) || showSuggestBulb ) && (() => {
            const grouped = (msg.citations || []).reduce((acc, cite) => {
              const existing = acc.find(c => c.sourceName === cite.sourceName);
              if (existing) {
                existing.count = (existing.count || 1) + 1;
              } else {
                acc.push({ ...cite, count: 1 });
              }
              return acc;
            }, []);
            return (
              <div className="mt-3 pt-3" style={{ borderTop: (msg.citations && msg.citations.length > 0) ? '1px solid var(--color-border)' : 'none' }}>
                <div className="flex flex-wrap items-center gap-2">
                  {grouped.map((group, idx) => (
                    <button
                      key={idx}
                      onClick={() => setActiveCitation(group)}
                      title={group.excerpt}
                      className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-xl border interactive cursor-pointer"
                      style={{ backgroundColor: 'var(--color-bg-subtle)', borderColor: 'var(--color-border)' }}
                    >
                      {group.isImage ? (
                        <svg className="w-3.5 h-3.5 text-blue-500 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14M14 8h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z" />
                        </svg>
                      ) : (
                        <svg className="w-3.5 h-3.5 text-blue-500 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                        </svg>
                      )}
                      <span className="text-[12px] font-medium truncate max-w-[160px]" style={{ color: 'var(--color-text-primary)' }}>
                        {group.sourceName}
                      </span>
                      {group.count > 1 && (
                        <span className="ml-1 text-[10px] font-bold text-blue-600 dark:text-blue-400 bg-blue-500/10 px-1.5 py-0.5 rounded-md">
                          ×{group.count}
                        </span>
                      )}
                    </button>
                  ))}

                  {showSuggestBulb && (
                    <button
                      onClick={onSuggestQuestions}
                      disabled={isGeneratingQuestions}
                      className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl border text-[12px] font-medium interactive cursor-pointer transition-all hover:border-blue-500/50 hover:text-blue-500 hover:shadow-sm"
                      style={{ backgroundColor: 'var(--color-bg-subtle)', borderColor: 'var(--color-border)', color: 'var(--color-text-primary)' }}
                    >
                      {isGeneratingQuestions ? (
                        <svg className="w-3.5 h-3.5 animate-spin text-blue-500" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24"><circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"></circle><path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path></svg>
                      ) : (
                        <svg className="w-3.5 h-3.5 text-blue-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2"><path strokeLinecap="round" strokeLinejoin="round" d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" /></svg>
                      )}
                      Suggest Follow-up Questions
                    </button>
                  )}
                </div>
              </div>
            );
          })()}
          </div>
          


          {msg.status === 'error' && (
            <div className="mt-2 flex flex-col items-start gap-2">
              <span className="text-[12px] text-red-500 font-medium flex items-center gap-1.5 opacity-90">
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2.5" d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
                Something went wrong.
              </span>
            </div>
          )}

          {msg.status === 'cancelled' && (
            <div className="mt-2 flex flex-col items-start gap-2">
              <span className="text-[12px] font-medium flex items-center gap-1.5 opacity-90" style={{ color: 'var(--color-text-secondary)' }}>
                <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 24 24">
                  <rect x="6" y="6" width="12" height="12" rx="2" />
                </svg>
                Generation stopped.
              </span>
              
              {/* If ingestion is still happening, show it underneath */}
              {msg.ingestionStatus && Object.keys(msg.ingestionStatus).length > 0 && (
                <div className="mt-1 text-[12px] font-medium flex items-center gap-1.5 text-blue-500">
                  <span>📚</span>
                  Preparing {Object.keys(msg.ingestionStatus).length} document(s) in the background...
                </div>
              )}
            </div>
          )}
          </div>
        </div>
      </div>
  );
}, (prevProps, nextProps) => {
  // If either was or is streaming, re-render
  if (prevProps.isStreaming || nextProps.isStreaming) return false;
  
  // Custom deep comparison for properties that change
  return (
    prevProps.msg.id === nextProps.msg.id &&
    prevProps.msg.text === nextProps.msg.text &&
    prevProps.msg.status === nextProps.msg.status &&
    // Strict equality check on arrays rather than length, so we catch 
    // content/confidence updates even if the array length stays the same.
    prevProps.msg.progressEvents === nextProps.msg.progressEvents &&
    prevProps.msg.citations === nextProps.msg.citations &&
    prevProps.msg.visuals === nextProps.msg.visuals &&
    prevProps.isGeneratingQuestions === nextProps.isGeneratingQuestions &&
    prevProps.showSuggestBulb === nextProps.showSuggestBulb
  );
});

export default ChatMessage;
