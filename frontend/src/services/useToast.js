/**
 * useToast.js
 * Simple hook so Dashboard can show toasts for ingest success/error
 * without managing 3 separate state variables.
 *
 * Usage:
 *   const { toast, showToast, clearToast } = useToast();
 *   <Toast message={toast.message} type={toast.type} onClose={clearToast} />
 */
import { useState, useCallback } from 'react';

export function useToast() {
  const [toast, setToast] = useState({ message: '', type: 'info' });

  const showToast = useCallback((message, type = 'info') => {
    setToast({ message, type });
  }, []);

  const clearToast = useCallback(() => {
    setToast({ message: '', type: 'info' });
  }, []);

  return { toast, showToast, clearToast };
}
