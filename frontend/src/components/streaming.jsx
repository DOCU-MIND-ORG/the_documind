import React, { useMemo } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

/**
 * StreamingMarkdown
 *
 * Splits streamed text into:
 *   - completedText: everything before the last newline → parsed as markdown (stable)
 *   - streamingLine: the current incomplete line → rendered as plain text (no flicker)
 *
 * Once streaming stops (isStreaming=false), the full text is rendered as markdown.
 */
export default function Streaming({ text, isStreaming, citations, onCitationClick }) {
  const { completedText, streamingLine } = useMemo(() => {
    if (!text) return { completedText: '', streamingLine: '' };

    // Convert [CITE:1] into markdown links: [1](#cite-1)
    // We split by triple backticks so we don't replace inside code blocks
    const parts = text.split(/(```[\s\S]*?```)/g);
    const processedText = parts.map((part, index) => {
      // Even indices are outside code blocks, odd indices are inside code blocks
      if (index % 2 === 0) {
        return part.replace(/\[CITE:(\d+)\]/g, '[$1](#cite-$1)');
      }
      return part;
    }).join('');

    if (!isStreaming) return { completedText: processedText, streamingLine: '' };

    const lastNewline = processedText.lastIndexOf('\n');
    if (lastNewline === -1) {
      return { completedText: '', streamingLine: processedText };
    }

    return {
      completedText: processedText.slice(0, lastNewline + 1),
      streamingLine: processedText.slice(lastNewline + 1),
    };
  }, [text, isStreaming]);

  const components = useMemo(() => ({
    a: ({ node, ...props }) => {
      if (props.href && props.href.startsWith('#cite-')) {
        const citeIndex = parseInt(props.href.replace('#cite-', ''), 10);
        // citation index is 1-based, citations array is 0-based
        const citation = citations && citations[citeIndex - 1];
        if (citation) {
          return (
            <button
              type="button"
              onClick={(e) => { e.preventDefault(); onCitationClick(citation); }}
              className="inline-flex items-center justify-center w-[18px] h-[18px] rounded-full bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-400 text-[10px] font-bold mx-0.5 align-text-top translate-y-[-2px] hover:bg-blue-200 dark:hover:bg-blue-500/30 transition-colors"
              title={`Source: ${citation.sourceName}`}
            >
              {citeIndex}
            </button>
          );
        }
      }
      return <a {...props} target="_blank" rel="noopener noreferrer" className="text-blue-500 hover:underline" />;
    }
  }), [citations, onCitationClick]);

  return (
    <div className="streaming-markdown prose prose-sm dark:prose-invert max-w-none text-[14.5px] leading-relaxed break-words">
      {completedText && (
        <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
          {completedText}
        </ReactMarkdown>
      )}
      {streamingLine && (
        <span className="streaming-line">{streamingLine}</span>
      )}
      {isStreaming && (
        <span className="inline-block ml-1.5 align-middle animate-pulse" aria-hidden="true" style={{ transform: 'translateY(-1px)' }}>
          <svg className="w-3.5 h-3.5 text-[#A100FF]" viewBox="0 0 24 24" fill="currentColor" xmlns="http://www.w3.org/2000/svg">
            <path d="m.66 16.95 13.242-4.926L.66 6.852V0l22.68 9.132v5.682L.66 24Z"/>
          </svg>
        </span>
      )}
    </div>
  );
}