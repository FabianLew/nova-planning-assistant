import { describe, expect, it } from 'vitest';
import { TurnTracker, type Effect } from './turns';
import { percentile } from './stats';

const kinds = (effects: Effect[]) => effects.map((e) => e.kind);

describe('TurnTracker', () => {
  it('reports ready once and moves to listening', () => {
    const t = new TurnTracker(650);
    expect(kinds(t.handle({ type: 'session.created' }, 0))).toEqual(['ready', 'state']);
    expect(t.handle({ type: 'session.updated' }, 1)).toEqual([]);
  });

  it('measures end of speech to first audio and adds the VAD silence', () => {
    const t = new TurnTracker(650);
    t.handle({ type: 'input_audio_buffer.speech_stopped' }, 1000);
    t.handle({ type: 'response.created', response: { id: 'r1' } }, 1100);
    const out = t.handle({ type: 'output_audio_buffer.started' }, 1800);
    expect(out).toContainEqual({ kind: 'state', state: 'speaking' });
    expect(out).toContainEqual({ kind: 'metric', type: 'latency.reply', payload: { ms: 800, vadSilenceMs: 650 } });
  });

  it('handles barge-in: clears caption, ignores late deltas, measures time to silence, marks transcript', () => {
    const t = new TurnTracker(650);
    t.handle({ type: 'response.created', response: { id: 'r1' } }, 0);
    t.handle({ type: 'output_audio_buffer.started' }, 10);
    t.handle({ type: 'response.output_audio_transcript.delta', response_id: 'r1', delta: 'O siódmej proponuję bieg, ' }, 20);

    const barge = t.handle({ type: 'input_audio_buffer.speech_started' }, 1000);
    expect(barge).toContainEqual({ kind: 'caption', text: '' });

    // a delta of the old turn arriving late is dropped
    expect(t.handle({ type: 'response.output_audio_transcript.delta', response_id: 'r1', delta: 'potem…' }, 1050)).toEqual([]);

    const cleared = t.handle({ type: 'output_audio_buffer.cleared' }, 1300);
    expect(cleared).toContainEqual({ kind: 'metric', type: 'latency.interrupt', payload: { ms: 300 } });
    expect(cleared).toContainEqual({ kind: 'state', state: 'listening' });

    const done = t.handle({
      type: 'response.done',
      response: {
        id: 'r1', status: 'cancelled',
        output: [{ id: 'item_a', type: 'message', role: 'assistant', content: [{ type: 'output_audio', transcript: 'O siódmej proponuję bieg' }] }],
      },
    }, 1310);
    expect(done).toEqual([{ kind: 'message', role: 'assistant', content: 'O siódmej proponuję bieg', interrupted: true, providerItemId: 'item_a' }]);
  });

  it('a normal completed reply is not marked interrupted', () => {
    const t = new TurnTracker(650);
    t.handle({ type: 'response.created', response: { id: 'r2' } }, 0);
    const done = t.handle({
      type: 'response.done',
      response: { id: 'r2', status: 'completed', output: [{ id: 'i2', type: 'message', role: 'assistant', content: [{ transcript: 'Spacer o jedenastej.' }] }] },
    }, 5);
    expect(done[0]).toMatchObject({ interrupted: false, content: 'Spacer o jedenastej.' });
  });

  it('speech while the assistant is silent is not an interruption', () => {
    const t = new TurnTracker(650);
    const out = t.handle({ type: 'input_audio_buffer.speech_started' }, 0);
    expect(out).toEqual([{ kind: 'userSpeaking', speaking: true }]);
    expect(t.handle({ type: 'output_audio_buffer.cleared' }, 10).some((e) => e.kind === 'metric')).toBe(false);
  });

  it('turns user transcripts into messages and failures into gap events', () => {
    const t = new TurnTracker(650);
    expect(t.handle({ type: 'conversation.item.input_audio_transcription.completed', item_id: 'u1', transcript: ' Po nocnym montażu? ' }, 0))
      .toEqual([{ kind: 'message', role: 'user', content: 'Po nocnym montażu?', interrupted: false, providerItemId: 'u1' }]);
    expect(t.handle({ type: 'conversation.item.input_audio_transcription.failed', item_id: 'u2' }, 0)[0])
      .toMatchObject({ kind: 'event', type: 'transcript.gap' });
  });
});

describe('function calls', () => {
  it('are emitted from a completed response only', () => {
    const t = new TurnTracker(650);
    const fc = { id: 'f1', type: 'function_call', call_id: 'call_1', name: 'create_task', arguments: '{"title":"Spacer"}' };
    expect(t.handle({ type: 'response.done', response: { id: 'r1', status: 'completed', output: [fc] } }, 0))
      .toEqual([{ kind: 'toolCalls', calls: [{ callId: 'call_1', name: 'create_task', arguments: '{"title":"Spacer"}' }] }]);
    expect(t.handle({ type: 'response.done', response: { id: 'r2', status: 'cancelled', output: [fc] } }, 0)).toEqual([]);
  });
});

describe('percentile', () => {
  it('uses nearest rank', () => {
    const v = Array.from({ length: 20 }, (_, i) => (i + 1) * 100);
    expect(percentile(v, 95)).toBe(1900);
    expect(percentile([], 95)).toBeNull();
  });
});
