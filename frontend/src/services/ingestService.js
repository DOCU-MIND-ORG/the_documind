/**
 * ingestService.js
 * Detects file type and calls the correct backend /ingest/* endpoint.
 * Returns { documentId, fileName, sourceType, chunksCreated, message }
 */

import { ingestApi } from './api.js';

const MAX_FILE_BYTES = 10 * 1024 * 1024; // 10 MB

const ACCEPTED_TYPES = {
  pdf:   ['application/pdf'],
  image: ['image/jpeg', 'image/png', 'image/webp', 'image/gif'],
  text:  ['text/plain', 'text/markdown', 'text/x-markdown'],
};

/**
 * Detect input type from a File object.
 * Returns 'pdf' | 'image' | 'text' | null
 */
export function detectFileType(file) {
  const mime = file.type;
  const name = file.name.toLowerCase();

  if (ACCEPTED_TYPES.pdf.includes(mime))   return 'pdf';
  if (ACCEPTED_TYPES.image.includes(mime)) return 'image';
  if (ACCEPTED_TYPES.text.includes(mime))  return 'text';

  // Fallback by extension
  if (name.endsWith('.pdf'))  return 'pdf';
  if (name.endsWith('.md') || name.endsWith('.txt')) return 'text';
  if (name.endsWith('.png') || name.endsWith('.jpg') ||
      name.endsWith('.jpeg') || name.endsWith('.webp')) return 'image';

  return null;
}

/**
 * Validate and ingest a file.
 * Throws descriptive errors that map to the 4 required error states.
 */
export async function ingestFile(file, sessionId) {
  // Error state 1: file too large
  if (file.size > MAX_FILE_BYTES) {
    throw new Error(`File "${file.name}" is too large. Maximum size is 10 MB.`);
  }

  // Error state 2: unsupported format
  const type = detectFileType(file);
  if (!type) {
    throw new Error(
      `Unsupported file type "${file.type || file.name.split('.').pop()}". ` +
      `Please upload a PDF, image (JPG/PNG/WebP), or text file (TXT/MD).`
    );
  }

  // Call the correct endpoint
  switch (type) {
    case 'pdf':   return ingestApi.pdf(file, sessionId);
    case 'image': return ingestApi.image(file, sessionId);
    case 'text':  return ingestApi.text(file, sessionId);
    default:      throw new Error('Unknown file type');
  }
}

/**
 * Ingest a Wikipedia URL.
 * Throws descriptive error for invalid / unreachable URLs.
 */
export async function ingestWikipedia(url, sessionId) {
  if (!url || !url.trim()) {
    throw new Error('Please enter a Wikipedia URL.');
  }
  if (!url.includes('wikipedia.org/wiki/')) {
    throw new Error('Please enter a valid Wikipedia URL (e.g. https://en.wikipedia.org/wiki/Java).');
  }
  try {
    return await ingestApi.wikipedia(url.trim(), sessionId);
  } catch (err) {
    // Error state 3: URL unreachable
    if (err.message.includes('fetch') || err.message.includes('network')) {
      throw new Error('Could not reach that Wikipedia article. Please check the URL and try again.');
    }
    throw err;
  }
}
