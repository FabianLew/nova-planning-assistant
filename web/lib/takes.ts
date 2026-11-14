/**
 * Take log for editing (spec p.7). Independent of the voice session: a take has its own zero point, and
 * "Pauza zapisu" stops logging while the conversation may go on. Every event gets its time on the phone the
 * moment it happens and an id, so a retry after a network drop keeps the right offset and is not doubled.
 */
import { Api, type Take, type TakeEvent, type TakeEventInput, type TakeEventType } from './api';
import { newKey } from './hooks';
import { Outbox } from './outbox';

export type Speaker = 'creator' | 'assistant' | 'system';

export interface TakeApi {
  startTake: typeof Api.startTake;
  openTake: typeof Api.openTake;
  takeAction: typeof Api.takeAction;
  takeEvent: typeof Api.takeEvent;
  editTakeEvent: typeof Api.editTakeEvent;
}

export class TakeRecorder {
  private take: Take | null = null;
  private outbox: Outbox;
  private lostAt: number | null = null;

  constructor(
    private readonly onChange: (take: Take | null) => void,
    private readonly api: TakeApi = Api,
    private readonly now: () => number = () => Date.now(),
  ) {
    this.outbox = new Outbox((e) => console.warn('NOVA: take write rejected', e));
  }

  get current() {
    return this.take;
  }

  get recording() {
    return this.take?.status === 'recording';
  }

  /** Picks up a take left open (page reload on set). */
  async restore() {
    try {
      this.set((await this.api.openTake()) ?? null);
    } catch { /* offline: start fresh */ }
  }

  async start(label?: string, sessionId?: string | null) {
    this.set(await this.api.startTake(label, sessionId));
  }

  pause() {
    this.action('pause', 'paused');
  }

  /** Privacy: the record pauses at once; the caller also mutes the microphone. */
  privacy() {
    this.action('privacy', 'paused');
  }

  resume() {
    this.action('resume', 'recording');
  }

  stop() {
    this.action('stop', 'closed');
  }

  /** Logs one event if a take is recording. Returns the saved event once the server has it. */
  log(type: TakeEventType, speaker: Speaker | null, text: string | null, payload?: Record<string, unknown>): Promise<TakeEvent | null> {
    const t = this.take;
    if (!t || t.status !== 'recording') return Promise.resolve(null);
    const e: TakeEventInput = { clientEventId: newKey(), type, speaker, text, occurredAtMs: this.now(), payload };
    return new Promise((resolve) => {
      this.outbox.push(async () => {
        try {
          resolve(await this.api.takeEvent(t.id, e));
        } catch (err) {
          if (!(err instanceof TypeError)) resolve(null); // rejected (e.g. paused meanwhile)
          throw err; // the outbox retries network failures
        }
      });
    });
  }

  marker(text: string | null = null) {
    return this.log('marker', 'creator', text);
  }

  /** Second tap on "Do montażu +": add a description to the marker just set. */
  describe(event: TakeEvent, text: string) {
    const t = this.take;
    if (!t) return;
    this.outbox.push(() => this.api.editTakeEvent(t.id, event.id, { text }));
  }

  connectionLost(reason: string) {
    if (this.lostAt === null) this.lostAt = this.now();
    void this.log('connection_lost', 'system', `Utrata połączenia (${reason})`);
  }

  connectionRestored() {
    const gapMs = this.lostAt === null ? null : this.now() - this.lostAt;
    this.lostAt = null;
    void this.log('connection_restored', 'system', 'Połączenie wróciło');
    if (gapMs !== null) void this.log('gap', 'system', `Przerwa w rozmowie ${(gapMs / 1000).toFixed(1)} s`, { gapMs });
  }

  private action(name: 'pause' | 'privacy' | 'resume' | 'stop', status: Take['status']) {
    const t = this.take;
    if (!t || t.status === 'closed') return;
    // Optimistic: nothing more is logged from this moment. Sent through the same queue, so events
    // captured before the tap still land before the pause.
    this.set(status === 'closed' ? null : { ...t, status });
    this.outbox.push(async () => {
      const updated = await this.api.takeAction(t.id, name);
      if (this.take?.id === t.id) this.set(updated.status === 'closed' ? null : updated);
    });
  }

  private set(t: Take | null) {
    this.take = t;
    this.onChange(t);
  }
}

export function offsetLabel(ms: number): string {
  const m = Math.floor(ms / 60000), s = Math.floor(ms / 1000) % 60, milli = ms % 1000;
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}.${String(milli).padStart(3, '0')}`;
}

export const SPEAKER_LABEL: Record<string, string> = { creator: 'TWÓRCA', assistant: 'NOVA', system: 'SYSTEM' };

export const TYPE_LABEL: Record<string, string> = {
  sync: 'Synchronizacja', utterance: 'Wypowiedź', interruption: 'Przerwanie', decision: 'Decyzja', tool: 'Narzędzie',
  marker: 'Do montażu', gap: 'Przerwa', pause: 'Pauza zapisu', resume: 'Wznowienie', connection_lost: 'Utrata sieci',
  connection_restored: 'Sieć wróciła', privacy: 'Prywatność', stop: 'Koniec',
};
