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
              <div className="flex items-start gap-3 px-4 py-3 pb-0">
                <div className="flex items-center justify-center w-8 h-8 rounded shrink-0" style={{ backgroundColor: 'var(--color-bg-subtle)' }}>
                  <svg className="w-5 h-5 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                     <path strokeLinecap="round" strokeLinejoin="round" d="M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z" />
                  </svg>
                </div>
                <div className="flex flex-col min-w-0 pt-0.5">
                  <h3 className="text-[14px] font-bold truncate leading-tight" style={{ color: 'var(--color-text-primary)' }}>
                    {chunkSourceName}
                  </h3>
                  <span className="text-[12px] truncate mt-0.5" style={{ color: 'var(--color-text-tertiary)' }}>
                    DocuMind / {chunkSourceType || 'Documents'}
                  </span>
                </div>
              </div>

              <div className="px-4 py-3 mt-1">
                <div className="flex items-center justify-between gap-3 text-[13px] font-semibold">
                  <span style={{ color: 'var(--color-text-secondary)' }}>Relevance</span>
                  <div className="flex-1 h-[4px] bg-gray-800 rounded-full overflow-hidden mx-1 flex items-center">
                    <div 
                      className="h-full rounded-full" 
                      style={{ 
                        width: `${chunk.score != null ? Math.round(chunk.score * 100) : 0}%`, 
                        background: 'linear-gradient(to right, #3b82f6, #22c55e)'
                      }} 
                    />
                  </div>
                  <span className={chunk.score != null && chunk.score > 0.8 ? 'text-[#22c55e]' : 'text-blue-500'}>
                    {chunk.score != null ? Math.round(chunk.score * 100) : 0}%
                  </span>
                </div>
              </div>

              <div className="px-4 pb-3">
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
                
                <h4 className="text-[13px] font-bold mb-2" style={{ color: 'var(--color-text-secondary)' }}>Preview</h4>
                <div 
                  className={`text-[13.5px] leading-relaxed whitespace-pre-wrap font-sans ${!isExpanded ? 'line-clamp-4 cursor-pointer' : ''}`}
                  style={{ color: 'var(--color-text-tertiary)' }}
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
              </div>

              <div className="px-4 py-3" style={{ borderTop: '1px solid var(--color-border)' }}>
                {chunk.url ? (
                  <a 
                    href={chunk.url} 
                    target="_blank" 
                    rel="noopener noreferrer" 
                    className="flex items-center justify-center w-full py-2 rounded-md text-[13px] font-bold border hover:bg-gray-800 transition-colors"
                    style={{ color: 'var(--color-text-secondary)', borderColor: 'var(--color-border)' }}
                  >
                    Open source
                  </a>
                ) : (
                   <button 
                    className="flex items-center justify-center w-full py-2 rounded-md text-[13px] font-bold border hover:bg-gray-800 transition-colors"
                    style={{ color: 'var(--color-text-secondary)', borderColor: 'var(--color-border)' }}
                  >
                    Open source
                  </button>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
