import { describe, expect, it, vi } from 'vitest';
import type { Take, TakeEventInput } from './api';
import { offsetLabel, TakeRecorder, type TakeApi } from './takes';

function fakeApi() {
  const take: Take = { id: 't1', sessionId: null, date: '2026-11-10', label: null, number: 1, status: 'recording',
    startedAt: new Date(0).toISOString(), endedAt: null };
  const calls: string[] = [];
  const events: TakeEventInput[] = [];
  let online = true;
  const api: TakeApi = {
    startTake: async () => ({ ...take }),
    openTake: async () => undefined,
    takeAction: async (_id, action) => {
      if (!online) throw new TypeError('offline');
      calls.push(action);
      return { ...take, status: action === 'stop' ? 'closed' : action === 'resume' ? 'recording' : 'paused' };
    },
    takeEvent: async (_id, e) => {
      if (!online) throw new TypeError('offline');
      calls.push(e.type);
      events.push(e);
      return { id: 'e' + events.length, takeId: 't1', seq: events.length, type: e.type, speaker: e.speaker ?? null,
        text: e.text ?? null, occurredAt: '', offsetMs: e.occurredAtMs, isPrivate: false, payload: {} };
    },
    editTakeEvent: async (_id, eventId, p) => { calls.push(`edit:${eventId}:${p.text}`); return {} as any; },
  };
  return { api, calls, events, setOnline: (v: boolean) => { online = v; } };
}

describe('TakeRecorder', () => {
  it('logs nothing while paused and keeps pause after earlier events', async () => {
    const f = fakeApi();
    const r = new TakeRecorder(() => {}, f.api, () => 1000);
    await r.start();
    void r.log('utterance', 'creator', 'raz', undefined);
    r.pause();
    expect(await r.log('utterance', 'creator', 'nie zapisuj')).toBeNull();
    r.resume();
    const m = await r.marker();
    r.describe(m!, 'dobra reakcja');
    await vi.waitFor(() => expect(f.calls).toEqual(['utterance', 'pause', 'resume', 'marker', 'edit:e2:dobra reakcja']));
  });

  it('keeps the phone time of events sent after a network drop, and logs the gap', async () => {
    vi.useFakeTimers();
    const f = fakeApi();
    let now = 5000;
    const r = new TakeRecorder(() => {}, f.api, () => now);
    await r.start();
    f.setOnline(false);
    r.connectionLost('offline');
    now = 9000;
    r.connectionRestored();
    f.setOnline(true);
    await vi.advanceTimersByTimeAsync(3500);
    vi.useRealTimers();
    expect(f.events.map((e) => [e.type, e.occurredAtMs])).toEqual([['connection_lost', 5000], ['connection_restored', 9000], ['gap', 9000]]);
    expect(f.events[2].payload).toEqual({ gapMs: 4000 });
    expect(new Set(f.events.map((e) => e.clientEventId)).size).toBe(3);
  });

  it('formats offsets as mm:ss.mmm', () => {
    expect(offsetLabel(12400)).toBe('00:12.400');
    expect(offsetLabel(61005)).toBe('01:01.005');
  });
});
