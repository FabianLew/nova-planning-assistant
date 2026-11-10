/**
 * One shared SSE connection per page. EventSource reconnects by itself and sends Last-Event-ID, so the
 * server replays what was missed; duplicates are dropped by seq.
 */
export interface ServerEvent {
  seq: number;
  type: string;
  payload: Record<string, any>;
  occurredAt: string;
}

type Listener = (e: ServerEvent) => void;

const listeners = new Set<Listener>();
let source: EventSource | null = null;
let lastSeq = 0;
let starting: Promise<void> | null = null;

const TYPES = [
  'approval.requested', 'approval.decided', 'plan.updated', 'task.created', 'task.status', 'busy.changed',
  'memory.changed', 'memory.deleted', 'tool.executed', 'experiment.changed', 'budget.changed', 'review.saved',
];

async function open() {
  if (source || starting) return starting ?? undefined;
  starting = (async () => {
    try {
      const res = await fetch('/api/events/last', { credentials: 'same-origin' });
      if (res.ok) lastSeq = Number(await res.text()) || 0;
    } catch { /* offline: start from 0 is fine, we dedupe */ }
    source = new EventSource(`/api/events/stream?after=${lastSeq}`, { withCredentials: true });
    const handle = (m: MessageEvent) => {
      const e = JSON.parse(m.data) as ServerEvent;
      if (e.seq <= lastSeq) return;
      lastSeq = e.seq;
      listeners.forEach((l) => l(e));
    };
    TYPES.forEach((t) => source!.addEventListener(t, handle as EventListener));
    starting = null;
  })();
  return starting;
}

export function subscribe(listener: Listener): () => void {
  listeners.add(listener);
  void open();
  return () => {
    listeners.delete(listener);
  };
}
