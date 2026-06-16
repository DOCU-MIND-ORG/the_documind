/**
 * CitationCard.jsx
 * Renders source citation chips below a bot message.
 * Props: citations — array of { chunkId, documentName, sourceType, chunkIndex, excerpt }
 */

import React, { useState } from 'react';

const SOURCE_ICONS = {
  pdf:       '📄',
  image:     '🖼️',
  markdown:  '📝',
  txt:       '📝',
  wikipedia: '🌐',
};

export default function CitationCard({ citations }) {
  const [expanded, setExpanded] = useState(null);

  if (!citations || citations.length === 0) return null;

  return (
    <div style={styles.wrapper}>
      <span style={styles.label}>Sources</span>
      <div style={styles.chips}>
        {citations.map((c, i) => (
          <div key={c.chunkId || i} style={styles.chipWrapper}>
            <button
              style={styles.chip}
              onClick={() => setExpanded(expanded === i ? null : i)}
              title={c.excerpt}
            >
              <span>{SOURCE_ICONS[c.sourceType] || '📎'}</span>
              <span style={styles.chipName}>
                [{i + 1}] {c.documentName || 'Source'}
              </span>
            </button>

            {/* Expanded excerpt */}
            {expanded === i && (
              <div style={styles.excerpt}>
                <div style={styles.excerptHeader}>
                  {c.documentName} — chunk {c.chunkIndex + 1}
                </div>
                <p style={styles.excerptText}>"{c.excerpt}..."</p>
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

const styles = {
  wrapper: {
    marginTop: '8px',
    display: 'flex',
    flexDirection: 'column',
    gap: '6px',
  },
  label: {
    fontSize: '11px',
    fontWeight: 600,
    color: 'var(--color-text-tertiary, #888)',
    textTransform: 'uppercase',
    letterSpacing: '0.05em',
  },
  chips: {
    display: 'flex',
    flexWrap: 'wrap',
    gap: '6px',
  },
  chipWrapper: {
    display: 'flex',
    flexDirection: 'column',
    gap: '4px',
  },
  chip: {
    display: 'inline-flex',
    alignItems: 'center',
    gap: '4px',
    padding: '3px 10px',
    borderRadius: '999px',
    border: '1px solid var(--color-border-secondary, #e0e0e0)',
    background: 'var(--color-bg-secondary, #f5f5f5)',
    cursor: 'pointer',
    fontSize: '12px',
    color: 'var(--color-text-secondary, #555)',
    transition: 'background 0.15s',
  },
  chipName: {
    maxWidth: '160px',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  },
  excerpt: {
    background: 'var(--color-bg-tertiary, #fafafa)',
    border: '1px solid var(--color-border-secondary, #e0e0e0)',
    borderRadius: '8px',
    padding: '8px 12px',
    maxWidth: '340px',
  },
  excerptHeader: {
    fontSize: '11px',
    fontWeight: 600,
    color: 'var(--color-text-tertiary, #888)',
    marginBottom: '4px',
  },
  excerptText: {
    fontSize: '12px',
    color: 'var(--color-text-secondary, #555)',
    margin: 0,
    lineHeight: 1.5,
    fontStyle: 'italic',
  },
};
