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
export default function Streaming({ text, isStreaming }) {
  const { completedText, streamingLine } = useMemo(() => {
    if (!isStreaming) return { completedText: text, streamingLine: '' };

    const lastNewline = text.lastIndexOf('\n');
    if (lastNewline === -1) {
      // No newline yet — everything is the current streaming line
      return { completedText: '', streamingLine: text };
    }

    return {
      completedText: text.slice(0, lastNewline + 1),
      streamingLine: text.slice(lastNewline + 1),
    };
  }, [text, isStreaming]);

  return (
    <div className="streaming-markdown">
      {completedText && (
        <ReactMarkdown remarkPlugins={[remarkGfm]}>
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