import { useState, useCallback } from "react";

/**
 * ImageDeck — stacked card UI for DocuMind extracted images/charts.
 *
 * Props:
 *   images  — array of { semanticId, imageUrl, thumbnailUrl, caption, sourceDocument }
 *   maxStack — how many cards show in the stacked view (default: 3)
 */
export default function ImageDeck({ images = [], maxStack = 3 }) {
  const [isExpanded, setIsExpanded] = useState(false);
  const [hoveredId, setHoveredId] = useState(null);
  const [activeOverlayImage, setActiveOverlayImage] = useState(null);

  const expand = useCallback(() => setIsExpanded(true), []);
  const collapse = useCallback(() => setIsExpanded(false), []);

  const handleStackKeyDown = useCallback((e) => {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      setIsExpanded(true);
    }
  }, []);

  if (images.length === 0) return null;

  return (
    <div className="mt-3 mb-1 font-sans">
      {isExpanded ? (
        <ExpandedGrid
          images={images}
          hoveredId={hoveredId}
          onHover={setHoveredId}
          onCollapse={collapse}
          onImageClick={setActiveOverlayImage}
        />
      ) : (
        <StackedDeck
          images={images}
          maxStack={maxStack}
          onExpand={expand}
          onKeyDown={handleStackKeyDown}
        />
      )}

      {activeOverlayImage && (
        <div 
          className="fixed inset-0 z-[100] bg-black/80 flex items-center justify-center p-4 cursor-pointer"
          onClick={() => setActiveOverlayImage(null)}
        >
          <img 
            src={activeOverlayImage} 
            alt="Enlarged view" 
            className="max-w-full max-h-full object-contain rounded-md shadow-2xl cursor-default"
            onClick={(e) => e.stopPropagation()}
          />
          <button 
            className="absolute top-4 right-4 text-white hover:text-gray-300 p-2 bg-black/50 rounded-full"
            onClick={() => setActiveOverlayImage(null)}
          >
            <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>
      )}
    </div>
  );
}

/* ─── Stacked state ─────────────────────────────────────────────────────────── */

function StackedDeck({ images, maxStack, onExpand, onKeyDown }) {
  const visibleCards = images.slice(0, maxStack);

  return (
    <div className="inline-flex flex-col gap-2 group mt-2">
      <div
        className="relative w-[130px] h-[100px] cursor-pointer outline-none active:scale-95 transition-transform duration-150"
        role="button"
        tabIndex={0}
        aria-label={`Expand ${images.length} extracted image${images.length !== 1 ? "s" : ""}`}
        onClick={onExpand}
        onKeyDown={onKeyDown}
      >
        {/* Folder Back */}
        <div className="absolute bottom-0 left-0 w-full h-[75px] bg-amber-500 rounded-lg rounded-tl-none shadow-sm" />
        {/* Folder Tab */}
        <div className="absolute bottom-[75px] left-0 w-[45px] h-[12px] bg-amber-500 rounded-t-md" />

        {/* Images (inside the folder) */}
        <div className="absolute bottom-[10px] left-0 w-full flex justify-center items-end pointer-events-none z-10">
          {visibleCards.map((img, index) => {
            const rotations = [-6, 6, 0];
            const rotation = rotations[index % 3];
            return (
              <div
                key={img.semanticId}
                className="absolute bottom-0 w-[90px] h-[65px] rounded border-[1.5px] border-white bg-gray-200 shadow-sm transition-all duration-300 ease-out origin-bottom group-hover:-translate-y-4"
                style={{
                  transform: `translateY(-${index * 3}px) rotate(${rotation}deg)`,
                  zIndex: 10 + (maxStack - index),
                }}
              >
                <img src={img.thumbnailUrl || img.imageUrl} alt="" className="w-full h-full object-cover rounded-sm" />
              </div>
            );
          })}
        </div>

        {/* Folder Front */}
        <div className="absolute bottom-0 left-0 w-full h-[55px] bg-amber-400 rounded-lg shadow-md z-20 border-t border-amber-300 transition-transform duration-300 origin-bottom" />
        
        {images.length > 1 && (
          <span className="absolute -top-3 -right-3 bg-blue-500 text-white text-[11px] font-bold w-[24px] h-[24px] rounded-full flex items-center justify-center z-30 border-[3px] border-[var(--color-bg-base)] shadow-sm pointer-events-none transition-transform duration-300 group-hover:scale-110">
            {images.length}
          </span>
        )}
      </div>

      <p className="text-[11px] text-tertiary flex items-center justify-center gap-1 select-none m-0 group-hover:text-secondary transition-colors">
        Click to view
      </p>
    </div>
  );
}

/* ─── Expanded state ─────────────────────────────────────────────────────────── */

function ExpandedGrid({ images, hoveredId, onHover, onCollapse, onImageClick }) {
  return (
    <div className="flex flex-wrap gap-2.5 animate-in fade-in slide-in-from-bottom-1 duration-200">
      {images.map((img) => (
        <ImageCard
          key={img.semanticId}
          img={img}
          isHovered={hoveredId === img.semanticId}
          onHover={() => onHover(img.semanticId)}
          onLeave={() => onHover(null)}
          onClick={() => onImageClick(img.imageUrl)}
        />
      ))}

      <div className="w-full flex">
        <button
          className="inline-flex items-center gap-1 text-xs text-white/35 bg-transparent border-none py-0.5 mt-0.5 cursor-pointer transition-colors duration-150 hover:text-white/65 focus-visible:outline focus-visible:outline-2 focus-visible:outline-blue-500 focus-visible:rounded-sm font-sans"
          onClick={onCollapse}
          aria-label="Collapse image deck"
        >
          <CollapseIcon />
          Collapse
        </button>
      </div>
    </div>
  );
}

function CollapseIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="mr-1">
      <polyline points="18 15 12 9 6 15" />
    </svg>
  );
}

function ImageCard({ img, isHovered, onHover, onLeave, onClick }) {
  return (
    <div
      className={`relative w-[120px] h-[90px] rounded-lg overflow-hidden border border-white/10 bg-gray-800 block no-underline transition-all duration-150 outline-none focus-visible:ring-2 focus-visible:ring-blue-500 ${isHovered ? 'scale-[1.04] border-blue-400/50' : ''} ${img.imageUrl ? 'cursor-pointer' : 'cursor-default'}`}
      onMouseEnter={onHover}
      onMouseLeave={onLeave}
      onClick={img.imageUrl ? onClick : undefined}
      aria-label={img.imageUrl ? `Open ${img.caption ?? "image"}` : img.caption}
    >
      <img src={img.thumbnailUrl || img.imageUrl} alt={img.caption ?? "Extracted content"} className="w-full h-full object-cover block" />

      {img.imageUrl && (
        <div className={`absolute inset-0 bg-black/50 flex items-center justify-center text-white transition-opacity duration-150 pointer-events-none ${isHovered ? 'opacity-100' : 'opacity-0'}`} aria-hidden="true">
          <ExternalLinkIcon />
        </div>
      )}

      {img.caption && (
        <div className="absolute bottom-0 left-0 right-0 py-1 px-1.5 bg-black/60 text-[10px] text-white/85 whitespace-nowrap overflow-hidden text-ellipsis pointer-events-none">
          {img.caption}
        </div>
      )}
    </div>
  );
}

function ExternalLinkIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6" />
      <polyline points="15 3 21 3 21 9" />
      <line x1="10" y1="14" x2="21" y2="3" />
    </svg>
  );
}
