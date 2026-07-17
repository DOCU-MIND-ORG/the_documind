import React, { useState, useEffect, useRef } from 'react';

// ── Human-readable rotating messages ──────────────────────────────────────────
const PREPARING_MESSAGES = [
  "Preparing document...",
  "Understanding its contents...",
  "Organizing information...",
  "Almost ready...",
];

const LONG_MESSAGES = [
  "Still working on this one...",
  "Large documents take a little longer.",
  "You don't need to do anything.",
];

// ── Animated doc icon with travelling sparkles ────────────────────────────────
const AnimatedDocIcon = ({ isDone, isFailed }) => {
  const [sparks, setSparks] = useState([]);
  const timerRef = useRef(null);

  useEffect(() => {
    if (isDone || isFailed) {
      setSparks([]);
      clearInterval(timerRef.current);
      return;
    }
    const spawn = () => {
      const id = Math.random().toString(36).slice(2);
      const x = 15 + Math.random() * 70;
      setSparks(prev => [...prev.slice(-5), { id, x }]);
      setTimeout(() => setSparks(prev => prev.filter(s => s.id !== id)), 900);
    };
    timerRef.current = setInterval(spawn, 550);
    return () => clearInterval(timerRef.current);
  }, [isDone, isFailed]);

  const color = isFailed ? '#ef4444' : isDone ? '#10b981' : '#3b82f6';

  return (
    <div className="relative w-9 h-9 shrink-0 flex items-center justify-center">
      {sparks.map(s => (
        <span
          key={s.id}
          className="doc-sparkle"
          style={{ left: `${s.x}%` }}
        />
      ))}
      <svg viewBox="0 0 24 24" fill="none" className="w-9 h-9" style={{ color }}>
        <path
          d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8l-6-6z"
          stroke="currentColor"
          strokeWidth="1.5"
          strokeLinecap="round"
          strokeLinejoin="round"
          fill={
            isFailed ? 'rgba(239,68,68,0.10)' :
            isDone   ? 'rgba(16,185,129,0.10)' :
                       'rgba(59,130,246,0.08)'
          }
        />
        <path
          d="M14 2v6h6M16 13H8M16 17H8M10 9H8"
          stroke="currentColor"
          strokeWidth="1.5"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
        {isDone && (
          <path
            className="doc-tick-draw"
            d="M8 12.5l2.5 2.5 5-5"
            stroke="#10b981"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        )}
      </svg>
    </div>
  );
};

// ── Animated check circle ──────────────────────────────────────────────────────
const AnimatedCheck = () => (
  <svg viewBox="0 0 20 20" fill="none" className="w-4 h-4 text-emerald-500 shrink-0">
    <circle cx="10" cy="10" r="9" stroke="currentColor" strokeWidth="1.5" fill="rgba(16,185,129,0.10)" />
    <path
      className="doc-tick-draw"
      d="M6.5 10l2.5 2.5 4-4"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
);

// ── Breathing/organic progress bar ────────────────────────────────────────────
const BreathingBar = ({ isLong }) => (
  <div className="w-full h-[3px] rounded-full mt-2 overflow-hidden"
       style={{ backgroundColor: 'var(--color-bg-subtle)' }}>
    <div className={isLong ? 'doc-bar-long' : 'doc-bar-breathe'} />
  </div>
);

// ── Pulsing active ring (REMOVED) ────────────────────────────────────────────────────────

// ── Individual file card ───────────────────────────────────────────────────────
const FileCard = ({ doc, isActive, isDone, isFailed, elapsed }) => {
  const [msgIdx, setMsgIdx] = useState(0);
  const isLong = elapsed > 10;

  useEffect(() => {
    if (!isActive || isDone || isFailed) return;
    const t = setInterval(() => setMsgIdx(p => p + 1), 3200);
    return () => clearInterval(t);
  }, [isActive, isDone, isFailed]);

  const messages = isLong ? LONG_MESSAGES : PREPARING_MESSAGES;
  const currentMsg = messages[msgIdx % messages.length];

  return (
    <div className={`doc-file-card ${isDone ? 'doc-file-card--done' : ''} ${isFailed ? 'doc-file-card--failed' : ''} ${isActive ? 'doc-file-card--active' : ''}`}>
      {/* Left: animated doc icon */}
      <AnimatedDocIcon isDone={isDone} isFailed={isFailed} />

      {/* Middle: filename + status message + bar */}
      <div className="flex-1 min-w-0">
        <p className="text-[13px] font-semibold leading-tight truncate" style={{ color: 'var(--color-text-primary)' }}>
          {doc.filename}
        </p>

        {isDone && (
          <p className="text-[11px] mt-0.5 font-medium text-emerald-600 dark:text-emerald-400 animate-fade-in-up">
            Ready
          </p>
        )}
        {isFailed && (
          <p className="text-[11px] mt-0.5 font-medium text-red-500 animate-fade-in-up">
            Failed — try re-uploading
          </p>
        )}
        {isActive && !isDone && !isFailed && (
          <>
            <p key={currentMsg} className="text-[11.5px] mt-0.5 doc-msg-rotate" style={{ color: 'var(--color-text-secondary)' }}>
              {currentMsg}
            </p>
            <BreathingBar isLong={isLong} />
          </>
        )}
      </div>

      {/* Right: status indicator */}
      <div className="shrink-0 flex items-center">
        {isDone   && <AnimatedCheck />}
        {isFailed && (
          <svg viewBox="0 0 20 20" fill="none" className="w-4 h-4 text-red-500">
            <circle cx="10" cy="10" r="9" stroke="currentColor" strokeWidth="1.5" fill="rgba(239,68,68,0.1)" />
            <path d="M7 7l6 6M13 7l-6 6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
          </svg>
        )}
        {isActive && !isDone && !isFailed && null}
      </div>
    </div>
  );
};

// ── Main exported component ────────────────────────────────────────────────────
export default function DocPreparationCard({ docs }) {
  const [elapsed, setElapsed]   = useState(0);
  const [phase, setPhase]       = useState('preparing'); // 'preparing' | 'done' | 'exiting'
  const startRef                = useRef(Date.now());
  const holdTimerRef            = useRef(null);

  // Elapsed clock
  useEffect(() => {
    const t = setInterval(
      () => setElapsed(Math.floor((Date.now() - startRef.current) / 1000)),
      1000
    );
    return () => clearInterval(t);
  }, []);

  const readyCount  = docs.filter(d => d.state === 'READY').length;
  const failedCount = docs.filter(d => d.state === 'FAILED').length;
  const allDone     = readyCount + failedCount === docs.length && docs.length > 0;

  // When everything is done: hold for 400 ms → trigger exit fade
  useEffect(() => {
    if (allDone && phase === 'preparing') {
      setPhase('done');
      holdTimerRef.current = setTimeout(() => setPhase('exiting'), 400);
    }
    return () => clearTimeout(holdTimerRef.current);
  }, [allDone, phase]);

  if (phase === 'exiting') return null; // let streaming take over

  const headerLabel =
    phase === 'done'
      ? 'Documents ready'
      : docs.length === 1
      ? 'Preparing your document'
      : 'Preparing your documents';

  const completedDocs = docs.filter(d => d.state === 'READY');
  const failedDocs    = docs.filter(d => d.state === 'FAILED');
  const activeDocs    = docs.filter(d => d.state !== 'READY' && d.state !== 'FAILED');
  
  // Spotlight exactly one document
  const spotlightDoc = activeDocs.length > 0 ? activeDocs[0] : null;
  const waitingCount = activeDocs.length > 1 ? activeDocs.length - 1 : 0;

  return (
    <div className={`doc-prep-card ${phase === 'done' ? 'doc-prep-card--done' : 'animate-fade-in-up'}`}>
      {/* Header row */}
      <div className="flex items-center gap-2 mb-3">
        <span className="text-[11px] font-semibold uppercase tracking-widest" style={{ color: 'var(--color-text-tertiary)' }}>
          {headerLabel}
        </span>
        {phase === 'done' && (
          <svg viewBox="0 0 16 16" fill="none" className="w-3.5 h-3.5 text-emerald-500 animate-fade-in-up">
            <circle cx="8" cy="8" r="7" stroke="currentColor" strokeWidth="1.5" fill="rgba(16,185,129,0.12)" />
            <path d="M5 8l2 2 4-4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        )}
      </div>

      <div className="flex flex-col gap-2">
        {/* Completed and Failed Documents (Compact) */}
        {(completedDocs.length > 0 || failedDocs.length > 0) && (
          <div className="flex flex-col gap-1.5 mb-2 px-1">
            {completedDocs.map(doc => (
              <div key={doc.jobId ?? doc.filename} className="flex items-center gap-2 animate-fade-in-up">
                <svg viewBox="0 0 20 20" fill="none" className="w-3.5 h-3.5 text-emerald-500 shrink-0">
                  <path d="M6 10l3 3 5-5" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
                <span className="text-[13px] font-medium text-secondary truncate">{doc.filename}</span>
              </div>
            ))}
            {failedDocs.map(doc => (
              <div key={doc.jobId ?? doc.filename} className="flex items-center gap-2 animate-fade-in-up">
                <svg viewBox="0 0 20 20" fill="none" className="w-3.5 h-3.5 text-red-500 shrink-0">
                  <path d="M6 6l8 8M14 6l-8 8" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
                <span className="text-[13px] font-medium text-red-500 truncate">{doc.filename}</span>
              </div>
            ))}
          </div>
        )}

        {/* Divider if we have both completed and an active spotlight */}
        {(completedDocs.length > 0 || failedDocs.length > 0) && spotlightDoc && (
          <div className="h-[1px] w-full bg-[var(--color-border)] my-1" />
        )}

        {/* The Spotlight Document */}
        {spotlightDoc && (
          <FileCard
            key={String(spotlightDoc.jobId ?? spotlightDoc.filename)}
            doc={spotlightDoc}
            isActive={true}
            isDone={false}
            isFailed={false}
            elapsed={elapsed}
          />
        )}
        
        {/* Waiting queue summary */}
        {waitingCount > 0 && (
          <div className="flex items-center gap-2 px-1 mt-1 animate-fade-in-up">
            <div className="w-1.5 h-1.5 rounded-full bg-[var(--color-bg-subtle)] border border-[var(--color-border-strong)] shrink-0" />
            <span className="text-[11.5px] font-medium text-tertiary">
              {waitingCount} more waiting...
            </span>
          </div>
        )}
      </div>

      {/* Footer / Long-wait reassurance */}
      {!allDone && (
        <div className="mt-4 pt-3 border-t border-[var(--color-border)]">
          <p className="text-[11.5px] font-medium text-secondary flex items-center gap-1.5 animate-fade-in-up">
             {elapsed > 10 
               ? "Large documents take a little longer. You don't need to do anything." 
               : "I'll answer as soon as everything is ready."}
          </p>
        </div>
      )}
    </div>
  );
}
