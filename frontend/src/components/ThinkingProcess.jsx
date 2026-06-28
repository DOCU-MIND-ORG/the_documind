import React, { useState, useEffect } from 'react';

const StepRow = ({ event, index, isLast, isComplete }) => {
  const [phase, setPhase] = useState('hidden'); 

  useEffect(() => {
    let timers = [];
    timers.push(setTimeout(() => setPhase('vis'), 10));
    timers.push(setTimeout(() => setPhase('appear'), 70));
    timers.push(setTimeout(() => setPhase('active'), 300));
    return () => timers.forEach(clearTimeout);
  }, []);

  useEffect(() => {
    if (phase !== 'hidden' && phase !== 'vis' && phase !== 'appear') {
      if (!isLast || isComplete) {
        setPhase('done');
      }
    }
  }, [isLast, isComplete, phase]);

  const rowClass = `v-row ${
    phase === 'vis' ? 'vis' : 
    phase === 'appear' ? 'vis appear-r' : 
    phase === 'active' ? 'vis active-r' : 
    phase === 'done' ? 'vis done-r' : ''
  }`;
  
  const nodeClass = `v-node ${
    phase === 'appear' ? 'appear' : 
    phase === 'active' ? 'active' : 
    phase === 'done' ? 'done' : ''
  }`;

  return (
    <div className={rowClass}>
      <div className="v-spine">
        <div className={nodeClass}></div>
        {!isLast && (
          <div className="v-connector">
            <div className={`v-fill ${phase === 'done' ? 'flowing' : ''}`}></div>
          </div>
        )}
      </div>
      <div className="v-content">
        <div className="v-title">{event.message}</div>
        <div className="v-sub">
           {event.metadata && Object.keys(event.metadata).length > 0 ? JSON.stringify(event.metadata) : ''}
        </div>
      </div>
    </div>
  );
};

export default function ThinkingProcess({ events, isComplete }) {
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

  return (
    <div className="mb-6 overflow-hidden text-[13px] animate-fade-in-up">
      <button 
        onClick={() => setIsOpen(!isOpen)}
        className="w-full flex items-center justify-between py-2 transition-colors opacity-80 hover:opacity-100"
      >
        <div className="flex items-center gap-2">
          {isComplete ? <CheckIcon /> : (
            <svg className="w-4 h-4 text-blue-500 animate-pulse" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
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
        <div className="pt-4 pb-2 pl-2">
          <div className="v-list">
            {events.map((event, index) => (
              <StepRow 
                key={event.id || index} 
                event={event} 
                index={index} 
                isLast={index === events.length - 1} 
                isComplete={isComplete} 
              />
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
