import React, { useEffect } from 'react';

export default function Modal({ isOpen, title, onClose, children, footer, size = 'md' }) {
  useEffect(() => {
    if (isOpen) {
      document.body.style.overflow = 'hidden';
    } else {
      document.body.style.overflow = '';
    }
    return () => { document.body.style.overflow = ''; };
  }, [isOpen]);

  if (!isOpen) return null;

  const sizeClasses = {
    sm: 'max-w-[420px]',
    md: 'max-w-[560px]',
    lg: 'max-w-[760px]',
    xl: 'max-w-[960px]',
  };

  return (
    <div className="fixed inset-0 bg-[#0a1628]/75 backdrop-blur-sm z-50 flex items-center justify-center p-4 animate-fade-in-up" onClick={onClose} role="dialog" aria-modal="true">
      <div
        className={`bg-[#16181d] border border-white/5 shadow-2xl rounded-2xl w-full max-h-[90vh] flex flex-col overflow-hidden ${sizeClasses[size] || sizeClasses.md}`}
        onClick={e => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-5 border-b border-white/5 bg-[#1e222b] text-white shrink-0">
          <h2 className="font-semibold text-lg text-white m-0">{title}</h2>
          <button className="w-8 h-8 rounded-lg bg-white/10 text-white/80 flex items-center justify-center hover:bg-white/20 transition-colors cursor-pointer" onClick={onClose} aria-label="Close modal">
            <svg width="20" height="20" viewBox="0 0 20 20" fill="none">
              <path d="M5 5l10 10M15 5L5 15" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
            </svg>
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto p-6 text-[#e2e8f0]">
          {children}
        </div>

        {/* Footer */}
        {footer && (
          <div className="px-6 py-4 border-t border-white/5 bg-[#1c1f26] shrink-0 flex justify-end gap-3">
            {footer}
          </div>
        )}
      </div>
    </div>
  );
}
