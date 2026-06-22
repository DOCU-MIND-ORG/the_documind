import { useEffect } from 'react';

const XIcon = () => (
  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
    <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
  </svg>
);

export default function CitationDrawer({ citation, onClose }) {
  useEffect(() => {
    if (!citation) return;
    const handleKeyDown = (e) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [citation, onClose]);

  if (!citation) return null;

  // citation.imageUrl comes back from the backend as a relative path like
  // "/files/images/uuid_signature.jpg" (see CitationService on the backend),
  // so it needs the API origin prepended — same pattern used for attachment
  // previews in Attachments.jsx.
  const API_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080';
  const resolvedImageUrl = citation.imageUrl
    ? (citation.imageUrl.startsWith('http') ? citation.imageUrl : `${API_URL}${citation.imageUrl}`)
    : null;

  return (
    <>
      <div 
        className="fixed inset-0 z-40 backdrop-blur-[2px] transition-opacity"
        style={{ backgroundColor: 'rgba(0, 0, 0, 0.2)' }}
        onClick={onClose}
      />

      <div 
        className="fixed inset-y-0 right-0 z-50 w-full sm:w-[420px] bg-white shadow-2xl flex flex-col drawer-slide-in"
        style={{ 
          backgroundColor: 'var(--color-bg-base)', 
          borderLeft: '1px solid var(--color-border)',
        }}
      >
        <div 
          className="flex items-center justify-between px-5 py-4 shrink-0"
          style={{ borderBottom: '1px solid var(--color-border)', backgroundColor: 'var(--color-bg-surface)' }}
        >
          <div className="flex items-center gap-2.5 min-w-0">
            <h2 className="text-[15px] font-semibold truncate" style={{ color: 'var(--color-text-primary)' }}>
              {citation.sourceName}
            </h2>
            {citation.sourceType && (
              <span 
                className="px-2 py-0.5 rounded text-[10px] font-medium tracking-wide uppercase shrink-0"
                style={{ backgroundColor: 'var(--color-bg-subtle)', color: 'var(--color-text-tertiary)', border: '1px solid var(--color-border)' }}
              >
                {citation.sourceType}
              </span>
            )}
          </div>
          <button 
            onClick={onClose}
            className="p-1.5 rounded-lg interactive shrink-0 ml-2"
            style={{ color: 'var(--color-text-tertiary)' }}
            onMouseEnter={e => e.currentTarget.style.color = 'var(--color-text-secondary)'}
            onMouseLeave={e => e.currentTarget.style.color = 'var(--color-text-tertiary)'}
          >
            <XIcon />
          </button>
        </div>

        <div 
          className="flex items-center gap-4 px-5 py-2.5 shrink-0 text-[12px] font-medium"
          style={{ borderBottom: '1px solid var(--color-border)', backgroundColor: 'var(--color-bg-subtle)', color: 'var(--color-text-secondary)' }}
        >
          <div className="flex items-center gap-1.5">
            <span style={{ color: 'var(--color-text-tertiary)' }}>Chunk</span>
            <span>#{citation.chunkIndex}</span>
          </div>
          <div className="w-[1px] h-3 bg-gray-300 dark:bg-gray-700" />
          <div className="flex items-center gap-1.5">
            <span style={{ color: 'var(--color-text-tertiary)' }}>Score</span>
            <span className={citation.score > 0.8 ? 'text-emerald-600 dark:text-emerald-400' : ''}>
              {citation.score != null ? citation.score : 'N/A'}
            </span>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-6">
          {citation.isImage && resolvedImageUrl && (
            <div className="mb-5">
              <p
                className="text-[11px] font-semibold mb-2 uppercase tracking-wider"
                style={{ color: 'var(--color-text-tertiary)' }}
              >
                Image
              </p>
              <img
                src={resolvedImageUrl}
                alt={citation.sourceName || 'Cited image'}
                className="w-full rounded-lg border object-contain max-h-[360px] bg-white"
                style={{ borderColor: 'var(--color-border)' }}
                onError={(e) => { e.currentTarget.style.display = 'none'; }}
              />
            </div>
          )}

          <p 
            className="text-[11px] font-semibold mb-3 uppercase tracking-wider"
            style={{ color: 'var(--color-text-tertiary)' }}
          >
            {citation.isImage ? 'Image Description' : 'Source Excerpt'}
          </p>
          <div 
            className="text-[14px] leading-relaxed whitespace-pre-wrap font-serif"
            style={{ color: 'var(--color-text-primary)' }}
          >
            {citation.fullExcerpt || citation.excerpt}
          </div>
        </div>
      </div>
    </>
  );
}
