import { createContext, useContext, useCallback } from 'react';
import { toast, Toaster } from 'sonner';

const ToastContext = createContext(null);

export function ToastProvider({ children }) {
  const showToast = useCallback((message, type = 'info', duration = 4000) => {
    if (type === 'error') {
      toast.error(message, { duration });
    } else if (type === 'success') {
      toast.success(message, { duration });
    } else {
      toast.info(message, { duration });
    }
  }, []);

  return (
    <ToastContext.Provider value={{ showToast }}>
      {children}
      <Toaster position="top-right" richColors closeButton />
    </ToastContext.Provider>
  );
}

export function useToast() {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast must be used inside ToastProvider');
  return ctx;
}
