import { useEffect, useState } from 'react';

const XIcon = () => (
  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
    <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
  </svg>
);

export default function CitationDrawer({ citations, onClose }) {
  const [activeTab, setActiveTab] = useState('all');
  const [expandedChunks, setExpandedChunks] = useState({});

  useEffect(() => {
    if (!citations) return;
    const handleKeyDown = (e) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [citations, onClose]);

  useEffect(() => {
    // Reset state when citations change
    setActiveTab('all');
    setExpandedChunks({});
  }, [citations]);

  if (!citations) return null;

  // Handle both single citation (from streaming click) and grouped citations (from badges)
  const chunksToRender = citations.chunks ? citations.chunks : [citations];
  const sourceName = citations.sourceName || 'Source';

  const API_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080';

  const isWiki = (type) => type && type.toUpperCase().includes('WIKI');

  const counts = {
    all: chunksToRender.length,
    docs: chunksToRender.filter(c => !isWiki(c.sourceType) && !c.isImage).length,
    images: chunksToRender.filter(c => c.isImage).length,
    wiki: chunksToRender.filter(c => isWiki(c.sourceType)).length,
  };

  const tabs = [
    { id: 'all', label: 'All', count: counts.all },
    { id: 'docs', label: 'Docs', count: counts.docs },
    { id: 'images', label: 'Images', count: counts.images },
    { id: 'wiki', label: 'Wiki', count: counts.wiki },
  ];

  const displayedChunks = chunksToRender.filter(c => {
    if (activeTab === 'all') return true;
    if (activeTab === 'docs') return !isWiki(c.sourceType) && !c.isImage;
    if (activeTab === 'images') return c.isImage;
    if (activeTab === 'wiki') return isWiki(c.sourceType);
    return true;
  });

  return (
    <div 
      className="absolute top-0 right-0 z-50 w-full sm:w-[420px] md:w-[450px] h-full flex flex-col drawer-slide-in shadow-2xl"
      style={{ 
        backgroundColor: 'var(--color-bg-base)', 
        borderLeft: '1px solid var(--color-border)',
      }}
    >
      <div 
        className="flex items-center justify-between px-5 pt-4 pb-2 shrink-0"
        style={{ backgroundColor: 'var(--color-bg-surface)' }}
      >
        <div className="flex flex-col min-w-0 gap-1">
          <h2 className="text-[17px] font-bold truncate" style={{ color: 'var(--color-text-primary)' }}>
            Sources
          </h2>
          <span className="text-[12px] font-medium" style={{ color: 'var(--color-text-secondary)' }}>
            {chunksToRender.length} reference{chunksToRender.length !== 1 ? 's' : ''} pulled for this entry
          </span>
        </div>
        <button 
          onClick={onClose}
          className="p-1.5 rounded-full interactive shrink-0 ml-2 border"
          style={{ color: 'var(--color-text-secondary)', borderColor: 'var(--color-border)' }}
        >
          <XIcon />
        </button>
      </div>

      <div 
        className="flex items-center gap-6 px-5 pt-2 shrink-0 overflow-x-auto"
        style={{ borderBottom: '1px solid var(--color-border)', backgroundColor: 'var(--color-bg-surface)' }}
      >
        {tabs.map(tab => {
          const isActive = activeTab === tab.id;
          return (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`flex items-center gap-1.5 pb-3 pt-1 border-b-2 transition-colors whitespace-nowrap ${isActive ? 'font-bold' : 'font-medium'}`}
              style={{
                borderColor: isActive ? 'var(--color-accent)' : 'transparent',
                color: isActive ? 'var(--color-text-primary)' : 'var(--color-text-secondary)'
              }}
            >
              <span className="text-[13px]">{tab.label}</span>
              <span 
                className="flex items-center justify-center min-w-[20px] h-[20px] px-1 rounded-full text-[10px] font-bold"
                style={{ 
                  backgroundColor: isActive ? 'var(--color-accent)' : 'var(--color-bg-hover)',
                  color: isActive ? '#fff' : 'var(--color-text-secondary)',
                  opacity: isActive ? 1 : 0.8
                }}
              >
                {tab.count}
              </span>
            </button>
          );
        })}
      </div>

      <div className="flex-1 overflow-y-auto px-5 py-5 space-y-4">
        {displayedChunks.map((chunk, idx) => {
          const resolvedImageUrl = chunk.imageUrl
            ? (chunk.imageUrl.startsWith('http') ? chunk.imageUrl : `${API_URL}${chunk.imageUrl}`)
            : null;
          
          // Use chunk-specific sourceType if available, otherwise fallback
          const chunkSourceType = chunk.sourceType || citations.sourceType || '';
          const chunkSourceName = chunk.sourceName || sourceName;
          
          const chunkKey = `${chunkSourceName}-${chunk.chunkIndex}-${idx}`;
          const isExpanded = !!expandedChunks[chunkKey];

          const toggleExpand = () => {
            setExpandedChunks(prev => ({ ...prev, [chunkKey]: !prev[chunkKey] }));
          };

          return (
            <div 
              key={`${activeTab}-${idx}`}
              className="rounded-xl border overflow-hidden shadow-sm"
              style={{ backgroundColor: 'var(--color-bg-surface)', borderColor: 'var(--color-border)' }}
            >
              <div 
                className="flex items-start justify-between px-4 py-3 shrink-0"
                style={{ borderBottom: '1px solid var(--color-border)', backgroundColor: 'var(--color-bg-subtle)' }}
              >
                <div className="flex flex-col min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="flex items-center justify-center w-6 h-6 rounded-md text-[12px] font-bold text-white bg-blue-600 shrink-0">
                      {chunk.chunkIndex}
                    </span>
                    <h3 className="text-[14px] font-bold truncate" style={{ color: 'var(--color-text-primary)' }}>
                      {chunkSourceName}
                    </h3>
                  </div>
                </div>
                {chunkSourceType && (
                  <span 
                    className="px-2 py-0.5 rounded text-[10px] font-bold tracking-wide uppercase shrink-0 mt-0.5 ml-2 border"
                    style={{ backgroundColor: 'var(--color-bg-surface)', color: 'var(--color-text-secondary)', borderColor: 'var(--color-border)' }}
                  >
                    {chunkSourceType}
                  </span>
                )}
              </div>

              <div className="px-4 py-3">
                {chunk.isImage && resolvedImageUrl && (
                  <div className="mb-4">
                    <img
                      src={resolvedImageUrl}
                      alt={chunkSourceName || 'Cited image'}
                      className="w-full rounded-lg border object-contain max-h-[280px] bg-white"
                      style={{ borderColor: 'var(--color-border)' }}
                      onError={(e) => { e.currentTarget.style.display = 'none'; }}
                    />
                  </div>
                )}
                
                <div 
                  className={`text-[14px] leading-relaxed whitespace-pre-wrap font-sans ${!isExpanded ? 'line-clamp-3 cursor-pointer' : ''}`}
                  style={{ color: 'var(--color-text-secondary)' }}
                  onClick={() => !isExpanded && toggleExpand()}
                >
                  {chunk.fullExcerpt || chunk.excerpt}
                </div>
                
                <button 
                  onClick={toggleExpand}
                  className="text-[11px] font-bold mt-2 flex items-center gap-1"
                  style={{ color: 'var(--color-text-tertiary)' }}
                >
                  {isExpanded ? (
                    <>Show less <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}><path strokeLinecap="round" strokeLinejoin="round" d="M5 15l7-7 7 7" /></svg></>
                  ) : (
                    <>Show more <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}><path strokeLinecap="round" strokeLinejoin="round" d="M19 9l-7 7-7-7" /></svg></>
                  )}
                </button>
                
                <div className="mt-3 pt-3 flex items-center justify-between text-[11px] font-medium" style={{ borderTop: '1px solid var(--color-border)', color: 'var(--color-text-tertiary)' }}>
                  <div className="flex items-center gap-1.5">
                    <span>Score:</span>
                    <span style={{ color: chunk.score > 0.8 ? 'var(--color-accent)' : 'inherit' }}>
                      {chunk.score != null ? chunk.score.toFixed(2) : 'N/A'}
                    </span>
                  </div>
                  {chunk.url && (
                    <a href={chunk.url} target="_blank" rel="noopener noreferrer" className="hover:underline flex items-center gap-1 font-semibold" style={{ color: 'var(--color-accent)' }}>
                      View Source <span aria-hidden="true">→</span>
                    </a>
                  )}
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
