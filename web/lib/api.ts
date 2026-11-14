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

export interface Task {
  id: string;
  planId: string;
  proposalId: string | null;
  title: string;
  startAt: string | null;
  durationMin: number;
  costGrosze: number;
  reason: string | null;
  status: 'proposed' | 'planned' | 'done' | 'skipped' | 'refused' | 'cancelled';
  refusalReason: string | null;
  source: 'user' | 'assistant';
}

export interface Approval {
  id: string;
  kind: 'plan_accept' | 'plan_change' | 'activity';
  planId: string;
  planVersion: number;
  args: Record<string, any>;
  summary: string;
  conflict: string | null;
  status: string;
  expiresAt: string;
  createdAt: string;
}

export interface BusyBlock { id: string; startsAt: string; endsAt: string; label: string }

export interface DayView {
  date: string;
  timeZone: string;
  plan: { id: string; date: string; version: number; status: 'draft' | 'approved' } | null;
  tasks: Task[];
  busy: BusyBlock[];
  approvals: Approval[];
}

export interface Memory {
  id: string;
  kind: 'fact' | 'inference' | 'episode';
  content: string;
  status: 'confirmed' | 'pending' | 'rejected';
  importance: number;
  sourceSessionId: string | null;
  sourceNote: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface Experiment {
  id: string; goal: string; startDate: string; days: number; workStart: string; workEnd: string; exclusions: string[];
  budgetGrosze: number; apiLimitGrosze: number; dayStart: string; baseline: Record<string, any>; rulesVersion: number;
  status: 'draft' | 'active' | 'paused' | 'completed' | 'ended_early'; activatedAt: string | null; endedAt: string | null;
}
export interface Budget { limitGrosze: number; spentGrosze: number; reservedGrosze: number; availableGrosze: number; overLimit: boolean }
export interface Review { date: string; rating: number; comment: string | null }
export interface ApiUsage { minutes: number; estimatedGrosze: number; limitGrosze: number; overLimit: boolean }
export interface ExperimentStatus {
  experiment: Experiment; dayIndex: number; experimentDate: string; budget: Budget; api: ApiUsage; refusals: number;
  tasksDone: number; tasksTotal: number; todayReview: Review | null; yesterdayReview: Review | null;
}
export interface DayReport { day: number; date: string; rating: number | null; comment: string | null; planned: number; done: number;
  skipped: number; refused: number; spentGrosze: number; plannedMinutes: number; actualMinutes: number }
export interface Report {
  experiment: Experiment; days: DayReport[]; averageRating: number | null; planned: number; done: number; refused: number; skipped: number;
  budget: Budget; expenses: { id: string; date: string; amountGrosze: number; description: string }[];
  refusals: { date: string; title: string; reason: string | null }[]; api: ApiUsage; baseline: Record<string, any>;
}
export interface ExperimentConfig {
  goal: string; startDate: string; workStart: string; workEnd: string; exclusions: string[]; budgetPln: number; apiLimitPln: number;
  dayStart: string; baselineRating?: number; baselineActivities?: number; baselineNote?: string;
}

export interface ToolOutcome { callId: string; name: string; status: string; output: Record<string, unknown>; replayed: boolean }

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
  instructions: (id: string) => api<{ instructions: string }>(`/api/sessions/${id}/instructions`),
  toolCall: (id: string, callId: string, name: string, args: string) =>
    api<ToolOutcome>(`/api/sessions/${id}/tool-calls`, { method: 'POST', body: { callId, name, arguments: args } }),

  today: () => api<DayView>('/api/days/today'),
  day: (date: string) => api<DayView>(`/api/days/${date}`),
  addTask: (date: string, t: { title: string; startTime?: string; durationMin?: number; key: string }) =>
    api<Task>(`/api/days/${date}/tasks`, { method: 'POST', body: t }),
  editTask: (id: string, t: { title?: string; startTime?: string; durationMin?: number; expectedPlanVersion: number }) =>
    api<Task>(`/api/tasks/${id}`, { method: 'PATCH', body: t }),
  taskStatus: (id: string, status: string, reason?: string) =>
    api<Task>(`/api/tasks/${id}/status`, { method: 'POST', body: { status, reason } }),
  addBusy: (date: string, b: { startTime: string; endTime: string; label: string }) =>
    api<BusyBlock>(`/api/days/${date}/busy`, { method: 'POST', body: b }),
  deleteBusy: (id: string) => api<void>(`/api/busy/${id}`, { method: 'DELETE' }),
  approvals: () => api<Approval[]>('/api/approvals'),
  decide: (id: string, decision: 'approve' | 'reject', key: string) =>
    api<{ outcome: string; approval: Approval }>(`/api/approvals/${id}/decision`, { method: 'POST', body: { decision, key } }),

  experiment: () => api<ExperimentStatus | undefined>('/api/experiments/current'),
  createExperiment: (c: ExperimentConfig) => api<Experiment>('/api/experiments', { method: 'POST', body: c }),
  updateExperiment: (id: string, c: ExperimentConfig) => api<Experiment>(`/api/experiments/${id}`, { method: 'PUT', body: c }),
  transition: (id: string, action: 'activate' | 'pause' | 'resume' | 'end', note?: string) =>
    api<Experiment>(`/api/experiments/${id}/transition`, { method: 'POST', body: { action, note } }),
  review: (date: string, rating: number, comment: string) =>
    api<Review>(`/api/experiments/current/reviews/${date}`, { method: 'PUT', body: { rating, comment } }),
  expense: (amountPln: number, description: string, key: string, taskId?: string) =>
    api('/api/experiments/current/expenses', { method: 'POST', body: { amountPln, description, key, taskId } }),
  report: (id: string) => api<Report>(`/api/experiments/${id}/report`),

  memories: (status?: string) => api<Memory[]>(`/api/memories${status ? `?status=${status}` : ''}`),
  addMemory: (content: string) => api<Memory>('/api/memories', { method: 'POST', body: { content } }),
  editMemory: (id: string, content: string, expectedVersion: number) =>
    api<Memory>(`/api/memories/${id}`, { method: 'PUT', body: { content, expectedVersion } }),
  confirmMemory: (id: string) => api<Memory>(`/api/memories/${id}/confirm`, { method: 'POST' }),
  rejectMemory: (id: string) => api<Memory>(`/api/memories/${id}/reject`, { method: 'POST' }),
  deleteMemory: (id: string) => api<void>(`/api/memories/${id}`, { method: 'DELETE' }),
};
