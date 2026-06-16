/**
 * UploadPanel.jsx
 * Drag-and-drop file upload + Wikipedia URL tab.
 * Handles all 4 input types and all 4 error states.
 *
 * Props:
 *   sessionId  {string}   current session UUID
 *   onSuccess  {fn}       called with ingest result when done
 *   onError    {fn}       called with error message string
 */

import React, { useRef, useState } from 'react';
import { ingestFile, ingestWikipedia, detectFileType } from '../services/ingestService.js';

export default function UploadPanel({ sessionId, onSuccess, onError }) {
  const [tab, setTab]         = useState('file');   // 'file' | 'wikipedia'
  const [dragging, setDragging] = useState(false);
  const [loading, setLoading]   = useState(false);
  const [progress, setProgress] = useState('');     // status message
  const [wikiUrl, setWikiUrl]   = useState('');
  const fileRef = useRef();

  // ── File upload ─────────────────────────────────────────────────────────────

  async function handleFile(file) {
    if (!file) return;
    if (!sessionId) {
      onError('Please create a session first before uploading.');
      return;
    }

    setLoading(true);
    setProgress(`Uploading ${file.name}…`);

    try {
      const type = detectFileType(file);
      if (type === 'pdf')   setProgress('Extracting text from PDF…');
      if (type === 'image') setProgress('Analysing image with AI Vision…');
      if (type === 'text')  setProgress('Reading file…');

      const result = await ingestFile(file, sessionId);

      setProgress(`✓ ${result.chunksCreated} chunks indexed from "${result.fileName}"`);
      onSuccess(result);

      // Clear progress after 3s
      setTimeout(() => setProgress(''), 3000);
    } catch (err) {
      setProgress('');
      onError(err.message);
    } finally {
      setLoading(false);
      if (fileRef.current) fileRef.current.value = '';
    }
  }

  // ── Drag events ─────────────────────────────────────────────────────────────

  function onDragOver(e)  { e.preventDefault(); setDragging(true); }
  function onDragLeave()  { setDragging(false); }
  function onDrop(e) {
    e.preventDefault();
    setDragging(false);
    const file = e.dataTransfer.files[0];
    if (file) handleFile(file);
  }

  // ── Wikipedia ────────────────────────────────────────────────────────────────

  async function handleWikipedia(e) {
    e.preventDefault();
    if (!wikiUrl.trim() || !sessionId) return;

    setLoading(true);
    setProgress('Fetching Wikipedia article…');

    try {
      const result = await ingestWikipedia(wikiUrl, sessionId);
      setProgress(`✓ Wikipedia: "${result.fileName}" indexed (${result.chunksCreated} chunks)`);
      setWikiUrl('');
      onSuccess(result);
      setTimeout(() => setProgress(''), 3000);
    } catch (err) {
      setProgress('');
      onError(err.message);
    } finally {
      setLoading(false);
    }
  }

  // ── Render ───────────────────────────────────────────────────────────────────

  return (
    <div style={styles.panel}>
      {/* Tabs */}
      <div style={styles.tabs}>
        {['file', 'wikipedia'].map(t => (
          <button
            key={t}
            style={{ ...styles.tab, ...(tab === t ? styles.tabActive : {}) }}
            onClick={() => setTab(t)}
          >
            {t === 'file' ? '📎 File' : '🌐 Wikipedia'}
          </button>
        ))}
      </div>

      {/* File tab */}
      {tab === 'file' && (
        <div
          style={{ ...styles.dropzone, ...(dragging ? styles.dropzoneActive : {}) }}
          onDragOver={onDragOver}
          onDragLeave={onDragLeave}
          onDrop={onDrop}
          onClick={() => !loading && fileRef.current?.click()}
        >
          <input
            ref={fileRef}
            type="file"
            accept=".pdf,.png,.jpg,.jpeg,.webp,.gif,.md,.txt"
            style={{ display: 'none' }}
            onChange={e => handleFile(e.target.files[0])}
          />
          {loading ? (
            <div style={styles.loadingRow}>
              <span style={styles.spinner} />
              <span style={styles.progressText}>{progress}</span>
            </div>
          ) : progress ? (
            <p style={styles.successText}>{progress}</p>
          ) : (
            <>
              <div style={styles.dropIcon}>⬆</div>
              <p style={styles.dropText}>
                Drag and drop or <span style={styles.link}>click to upload</span>
              </p>
              <p style={styles.dropHint}>PDF · PNG · JPG · WebP · MD · TXT — max 10 MB</p>
            </>
          )}
        </div>
      )}

      {/* Wikipedia tab */}
      {tab === 'wikipedia' && (
        <form onSubmit={handleWikipedia} style={styles.wikiForm}>
          <input
            type="url"
            value={wikiUrl}
            onChange={e => setWikiUrl(e.target.value)}
            placeholder="https://en.wikipedia.org/wiki/Java_(programming_language)"
            style={styles.wikiInput}
            disabled={loading}
          />
          <button type="submit" style={styles.wikiBtn} disabled={loading || !wikiUrl.trim()}>
            {loading ? '…' : 'Fetch'}
          </button>
          {progress && <p style={loading ? styles.progressText : styles.successText}>{progress}</p>}
        </form>
      )}
    </div>
  );
}

const styles = {
  panel: { display: 'flex', flexDirection: 'column', gap: '8px' },
  tabs: { display: 'flex', gap: '4px' },
  tab: {
    padding: '5px 14px', borderRadius: '6px', border: '1px solid transparent',
    background: 'none', cursor: 'pointer', fontSize: '13px',
    color: 'var(--color-text-secondary, #666)',
  },
  tabActive: {
    background: 'var(--color-bg-secondary, #f0f0f0)',
    border: '1px solid var(--color-border-secondary, #ddd)',
    color: 'var(--color-text-primary, #111)',
    fontWeight: 600,
  },
  dropzone: {
    border: '2px dashed var(--color-border-secondary, #d0d0d0)',
    borderRadius: '10px',
    padding: '28px 16px',
    textAlign: 'center',
    cursor: 'pointer',
    transition: 'border-color 0.2s, background 0.2s',
  },
  dropzoneActive: {
    borderColor: 'var(--color-accent, #4f46e5)',
    background: 'var(--color-accent-soft, #eff0fe)',
  },
  dropIcon: { fontSize: '28px', marginBottom: '8px' },
  dropText: { margin: '0 0 4px', fontSize: '14px', color: 'var(--color-text-secondary, #555)' },
  link: { color: 'var(--color-accent, #4f46e5)', fontWeight: 600 },
  dropHint: { margin: 0, fontSize: '12px', color: 'var(--color-text-tertiary, #999)' },
  loadingRow: { display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '10px' },
  spinner: {
    display: 'inline-block', width: '16px', height: '16px',
    border: '2px solid var(--color-border-secondary, #ddd)',
    borderTop: '2px solid var(--color-accent, #4f46e5)',
    borderRadius: '50%',
    animation: 'spin 0.7s linear infinite',
  },
  progressText: { margin: 0, fontSize: '13px', color: 'var(--color-text-secondary, #555)' },
  successText: { margin: 0, fontSize: '13px', color: '#16a34a', fontWeight: 500 },
  wikiForm: { display: 'flex', flexDirection: 'column', gap: '8px' },
  wikiInput: {
    padding: '9px 12px', borderRadius: '8px', fontSize: '13px',
    border: '1px solid var(--color-border-secondary, #d0d0d0)',
    outline: 'none', width: '100%', boxSizing: 'border-box',
  },
  wikiBtn: {
    padding: '9px 18px', borderRadius: '8px', border: 'none',
    background: 'var(--color-accent, #4f46e5)', color: '#fff',
    fontWeight: 600, fontSize: '13px', cursor: 'pointer',
    opacity: 1, transition: 'opacity 0.15s',
  },
};
