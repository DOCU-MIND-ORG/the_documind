import { useEffect } from 'react';

export default function Modal({ isOpen, title, onClose, children, footer, size = 'md' }) {
  useEffect(() => {
    document.body.style.overflow = isOpen ? 'hidden' : '';
    return () => { document.body.style.overflow = ''; };
  }, [isOpen]);

  if (!isOpen) return null;

  const sizeClasses = { sm: 'max-w-[420px]', md: 'max-w-[560px]', lg: 'max-w-[760px]', xl: 'max-w-[960px]' };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4 animate-fade-in-up"
      style={{ backgroundColor: 'rgba(0,0,0,0.55)', backdropFilter: 'blur(4px)' }}
      onClick={onClose}
      role="dialog"
      aria-modal="true"
    >
      <div
        className={`w-full max-h-[90vh] flex flex-col overflow-hidden rounded-2xl ${sizeClasses[size] || sizeClasses.md}`}
        style={{
          backgroundColor: 'var(--color-bg-elevated)',
          border:          '1px solid var(--color-border-strong)',
          boxShadow:       'var(--shadow-lg)',
        }}
        onClick={e => e.stopPropagation()}
      >
        <div
          className="flex items-center justify-between px-6 py-5 shrink-0"
          style={{ borderBottom: '1px solid var(--color-border)' }}
        >
          <h2 className="font-semibold text-lg text-primary m-0">{title}</h2>
          <button
            className="w-8 h-8 rounded-lg flex items-center justify-center text-secondary interactive cursor-pointer"
            onClick={onClose}
            aria-label="Close modal"
          >
            <svg width="18" height="18" viewBox="0 0 20 20" fill="none">
              <path d="M5 5l10 10M15 5L5 15" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
            </svg>
          </button>
        </div>
        <div className="flex-1 overflow-y-auto p-6 text-primary">
          {children}
        </div>
        {footer && (
          <div
            className="px-6 py-4 shrink-0 flex justify-end gap-3"
            style={{ borderTop: '1px solid var(--color-border)' }}
          >
            {footer}
          </div>
        )}
      </div>
    </div>
  );
}
