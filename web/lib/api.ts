/** Same-origin JSON client with CSRF (XSRF-TOKEN cookie -> X-XSRF-TOKEN header). */

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}

function readCookie(name: string): string | null {
  const m = document.cookie.match(new RegExp('(?:^|; )' + name.replace(/[-.]/g, '\\$&') + '=([^;]*)'));
  return m ? decodeURIComponent(m[1]) : null;
}

async function primeCsrf() {
  await fetch('/api/auth/csrf', { credentials: 'same-origin' });
}

export async function api<T>(path: string, init: { method?: string; body?: unknown; keepalive?: boolean } = {}, retried = false): Promise<T> {
  const method = init.method ?? 'GET';
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (method !== 'GET') {
    if (!readCookie('XSRF-TOKEN')) await primeCsrf();
    const token = readCookie('XSRF-TOKEN');
    if (token) headers['X-XSRF-TOKEN'] = token;
  }
  if (init.body !== undefined) headers['Content-Type'] = 'application/json';

  const res = await fetch(path, {
    method,
    headers,
    credentials: 'same-origin',
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
    keepalive: init.keepalive,
  });

  if (res.status === 403 && method !== 'GET' && !retried) {
    await primeCsrf(); // token cookie may have rotated
    return api<T>(path, init, true);
  }
  if (res.status === 401 && typeof window !== 'undefined' && !path.startsWith('/api/auth/')) {
    window.location.href = '/login/';
  }
  if (!res.ok) {
    let code = 'http_' + res.status;
    let message = res.statusText;
    try {
      const body = await res.json();
      code = body.code ?? code;
      message = body.message ?? message;
    } catch { /* not JSON */ }
    throw new ApiError(res.status, code, message);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export interface Me { id: string; username: string }

export interface StartedSession {
  sessionId: string;
  clientSecret: string;
  expiresAt: string;
  rtcUrl: string;
  model: string;
  voice: string;
  displayName: string;
  turnDetection: 'server_vad' | 'semantic_vad';
  vadSilenceMs: number;
  maxDurationSeconds: number;
}

export interface Profile {
  displayName: string;
  voice: string;
  instructions: string;
  turnDetection: 'server_vad' | 'semantic_vad';
  vadSilenceMs: number;
  vadEagerness: 'low' | 'medium' | 'high' | 'auto';
  version: number;
}

export interface Stats { count: number; p50?: number; p95?: number; max?: number; targetP95?: number; meetsTarget?: boolean }
export interface LatencyReport { reply: Stats; replyFromSpeechEnd: Stats; interrupt: Stats }

export const Api = {
  me: () => api<Me>('/api/auth/me'),
  login: (username: string, password: string) => api<Me>('/api/auth/login', { method: 'POST', body: { username, password } }),
  logout: () => api<void>('/api/auth/logout', { method: 'POST' }),
  profile: () => api<Profile>('/api/profile'),
  saveProfile: (p: Profile) => api<Profile>('/api/profile', { method: 'PUT', body: p }),
  startSession: () => api<StartedSession>('/api/sessions', { method: 'POST' }),
  connected: (id: string, providerCallId: string | null) =>
    api(`/api/sessions/${id}/connected`, { method: 'POST', body: { providerCallId } }),
  endSession: (id: string, reason: string) =>
    api(`/api/sessions/${id}/end`, { method: 'POST', body: { reason }, keepalive: true }),
  message: (id: string, m: { role: string; content: string; interrupted: boolean; providerItemId: string }) =>
    api(`/api/sessions/${id}/messages`, { method: 'POST', body: m }),
  event: (id: string, type: string, payload: Record<string, unknown>) =>
    api(`/api/sessions/${id}/events`, { method: 'POST', body: { type, payload } }),
  metrics: (id: string) => api<LatencyReport>(`/api/sessions/${id}/metrics`),
};
