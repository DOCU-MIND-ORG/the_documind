import React, { useState, useEffect } from 'react';

export default function ProgressIndicator({ events, isComplete }) {
  const [isOpen, setIsOpen] = useState(false);
  
  useEffect(() => {
    setIsOpen(!isComplete);
  }, [isComplete]);

  if (!events || events.length === 0) return null;

  const CheckIcon = () => (
    <div className="w-5 h-5 rounded-full bg-emerald-500/20 text-emerald-500 flex items-center justify-center shrink-0">
      <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
        <polyline points="20 6 9 17 4 12" />
      </svg>
    </div>
  );

  const SpinnerIcon = () => (
    <div className="w-5 h-5 flex items-center justify-center shrink-0">
      <div className="w-4 h-4 border-2 border-blue-500/30 border-t-blue-500 rounded-full animate-spin" />
    </div>
  );

  const InfoIcon = () => (
    <div className="w-5 h-5 rounded-full bg-blue-500/20 text-blue-500 flex items-center justify-center shrink-0">
      <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
        <path strokeLinecap="round" strokeLinejoin="round" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
      </svg>
    </div>
  );

  const renderIcon = (event, index) => {
    if (event.status === 'ERROR') {
      return (
        <div className="w-5 h-5 rounded-full bg-red-500/20 text-red-500 flex items-center justify-center shrink-0">
          <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="3">
            <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </div>
      );
    }
    
    // If it's the last event and the stream is not complete, show a spinner (unless it's explicitly INFO/COMPLETE)
    const isLast = index === events.length - 1;
    if (isLast && !isComplete && event.status === 'RUNNING') {
      return <SpinnerIcon />;
    }
    
    if (event.status === 'INFO') {
      return <InfoIcon />;
    }

    return <CheckIcon />;
  };

  return (
    <div className="mb-4 rounded-2xl border border-[var(--color-border)] bg-surface overflow-hidden text-[13px] animate-fade-in-up shadow-sm">
      <button 
        onClick={() => setIsOpen(!isOpen)}
        className="w-full flex items-center justify-between p-3 hover:bg-[var(--color-bg-hover)] transition-colors"
      >
        <div className="flex items-center gap-2">
          {isComplete ? <CheckIcon /> : (
            <svg className="w-4 h-4 text-blue-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
              <path strokeLinecap="round" strokeLinejoin="round" d="M13 10V3L4 14h7v7l9-11h-7z" />
            </svg>
          )}
          <span className="font-semibold text-primary">
            {isComplete ? 'Thought process' : 'Processing request...'}
          </span>
        </div>
        <svg 
          className={`w-4 h-4 text-tertiary transition-transform ${isOpen ? 'rotate-180' : ''}`} 
          fill="none" 
          viewBox="0 0 24 24" 
          stroke="currentColor"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </button>
      
      {isOpen && (
        <div className="p-4 pt-2 border-t border-[var(--color-border)] flex flex-col gap-4">
          {events.map((event, index) => (
            <div key={event.id || index} className="flex items-start gap-3 animate-fade-in">
              {renderIcon(event, index)}
              <div className="flex-1 min-w-0 pt-0.5">
                <p className="font-semibold text-primary">
                  {event.message}
                </p>
                {event.metadata && Object.keys(event.metadata).length > 0 && (
                   <div className="mt-1 text-[11px] text-tertiary font-mono break-words">
                     {JSON.stringify(event.metadata)}
                   </div>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
