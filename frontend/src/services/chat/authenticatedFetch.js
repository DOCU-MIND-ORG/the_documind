const BASE_URL = import.meta.env.VITE_API_URL || '';

async function refreshAccessToken() {
  const response = await fetch(`${BASE_URL}/auth/refresh`, {
    method: 'POST',
    credentials: 'include',
  });
  return response.ok;
}

/**
 * A generic authenticated fetch wrapper that handles 401 retries.
 * Unlike api.js, it returns the raw Response object, making it suitable for Streams and Blobs.
 */
export async function authenticatedFetch(path, options = {}, isRetry = false) {
  const response = await fetch(`${BASE_URL}${path}`, {
    credentials: 'include',
    ...options,
  });

  if (!response.ok) {
    if (response.status === 401 && !isRetry) {
      const refreshed = await refreshAccessToken();
      if (refreshed) {
        return authenticatedFetch(path, options, true);
      }
      window.dispatchEvent(new Event('auth-expired'));
      throw new Error('Session expired. Please log in again.');
    }
    // We do not consume the body on error here, because the caller might need to read it.
    throw new Error(`Request failed with status ${response.status}`);
  }

  return response;
}
