import React, { useState, useEffect } from 'react';

const StepRow = ({ event, index, isLast, isComplete }) => {
  const [phase, setPhase] = useState('hidden'); 

  useEffect(() => {
    let timers = [];
    timers.push(setTimeout(() => setPhase('appear'), index * 100 + 10));
    timers.push(setTimeout(() => setPhase('active'), index * 100 + 100));
    return () => timers.forEach(clearTimeout);
  }, [index]);

  useEffect(() => {
    if (phase !== 'hidden' && phase !== 'appear') {
      if (isComplete || (!isLast && event.status === 'DONE')) {
        setPhase('done');
      }
    }
  }, [isLast, isComplete, phase, event.status]);

  const isActive = phase === 'active' && !isComplete && isLast;
  const isDone = phase === 'done' || isComplete || (!isLast && phase !== 'hidden');

  return (
    <div className={`relative flex gap-3 transition-all duration-500 ease-out ${phase === 'hidden' ? 'opacity-0 translate-y-1' : 'opacity-100 translate-y-0'}`}>
      
      {/* Connector Line */}
      {!isLast && (
        <div className="absolute left-[7px] top-[20px] bottom-[-4px] w-[1px] bg-gray-200 dark:bg-gray-800">
          <div 
            className="w-full bg-blue-500 transition-all duration-700 ease-in-out origin-top" 
            style={{ height: isDone ? '100%' : '0%', opacity: isDone ? 1 : 0 }} 
          />
        </div>
      )}

      {/* Node / Orb */}
      <div className="relative z-10 flex flex-col items-center mt-1">
        <div className={`w-4 h-4 rounded-full flex items-center justify-center border-[1.5px] transition-all duration-500 ${
            isDone ? 'border-blue-500 bg-blue-50 dark:bg-blue-500/10' :
            isActive ? 'border-blue-500 bg-blue-500' :
            'border-gray-300 bg-white dark:border-gray-600 dark:bg-gray-900'
        }`}>
          {isDone ? (
            <div className="w-2 h-2 bg-blue-500 rounded-full" />
          ) : isActive ? (
             <div className="w-2 h-2 bg-white rounded-full animate-pulse" />
          ) : (
             <div className="w-1.5 h-1.5 bg-gray-300 dark:bg-gray-600 rounded-full" />
          )}
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 pb-3 pt-0.5">
        <div className={`text-[12px] font-medium transition-colors duration-300 ${
          isActive ? 'text-blue-600 dark:text-blue-400' :
          isDone ? 'text-gray-700 dark:text-gray-300' :
          'text-gray-400 dark:text-gray-500'
        }`}>
          {event.message}
        </div>
        
        {/* Minimal Metadata */}
        <div className={`mt-1 overflow-hidden transition-all duration-500 ease-out ${
          isDone || isActive ? 'max-h-20 opacity-100' : 'max-h-0 opacity-0'
        }`}>
          {(event.metadata?.optimized_query || event.metadata?.scope) && (
            <div className="flex flex-col gap-0.5 mt-0.5">
              {event.metadata?.optimized_query && (
                <div className="text-[11px] leading-snug">
                  <span className="text-gray-400 dark:text-gray-500">Parsed:</span>{' '}
                  <span className="text-gray-600 dark:text-gray-400 italic">"{event.metadata.optimized_query}"</span>
                </div>
              )}
              {event.metadata?.scope && (
                <div className="text-[11px] leading-snug flex items-center gap-1.5">
                  <span className="text-gray-400 dark:text-gray-500">Scope:</span>
                  <span className="text-gray-500 dark:text-gray-400 font-medium px-1.5 py-0.5 bg-gray-100 dark:bg-gray-800 rounded uppercase tracking-wider text-[9px]">{event.metadata.scope}</span>
                </div>
              )}
            </div>
          )}
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

  const filteredEvents = events ? events.filter(e => !e.message || !e.message.startsWith('Preparing your documents...')) : [];
  if (!filteredEvents || filteredEvents.length === 0) return null;

  return (
    <div className="mb-4 w-full max-w-sm">
      <div 
        onClick={() => setIsOpen(!isOpen)}
        className="inline-flex items-center gap-2 px-3 py-1.5 rounded-full border border-gray-200 dark:border-gray-800 bg-white dark:bg-[#1C1C1C] cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-900 transition-colors shadow-sm"
      >
        <span className="text-[14px]">
          {isComplete ? '💡' : '✨'}
        </span>
        <span className="text-[12px] font-semibold text-gray-700 dark:text-gray-300">
          {isComplete ? 'Process completed' : 'Thinking Process'}
        </span>
        <svg 
          className={`w-3.5 h-3.5 text-gray-500 transition-transform duration-300 ${isOpen ? 'rotate-180' : ''}`} 
          fill="none" 
          viewBox="0 0 24 24" 
          stroke="currentColor"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M19 9l-7 7-7-7" />
        </svg>
      </div>

      <div className={`mt-3 pl-2 overflow-hidden transition-all duration-500 ease-in-out ${isOpen ? 'max-h-[500px] opacity-100' : 'max-h-0 opacity-0'}`}>
        <div className="flex flex-col relative pt-1">
          {filteredEvents.map((event, idx) => (
            <StepRow 
              key={event.id || idx} 
              event={event} 
              index={idx} 
              isLast={idx === filteredEvents.length - 1} 
              isComplete={isComplete}
            />
          ))}
        </div>
      </div>
    </div>
  );
}
