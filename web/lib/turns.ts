/**
 * Pure turn tracker for the OpenAI Realtime event stream. It turns provider events into UI state,
 * final transcripts and M1 latency samples. No browser APIs here, so it is unit tested.
 *
 * Barge-in: with `interrupt_response: true` the provider cancels the reply as soon as the creator starts
 * speaking and, over WebRTC, clears the remaining audio and truncates the assistant item. We only need to
 * stop showing the stale caption, ignore late deltas from the cancelled response, and mark the transcript.
 */

export type VoiceState = 'idle' | 'connecting' | 'listening' | 'speaking' | 'disconnected' | 'ended' | 'error';

export type Effect =
  | { kind: 'state'; state: VoiceState }
  | { kind: 'ready' }
  | { kind: 'userSpeaking'; speaking: boolean }
  | { kind: 'caption'; text: string }
  | { kind: 'message'; role: 'user' | 'assistant'; content: string; interrupted: boolean; providerItemId: string }
  | { kind: 'event'; type: string; payload: Record<string, unknown> }
  | { kind: 'metric'; type: 'latency.reply' | 'latency.interrupt'; payload: Record<string, number> };

// Loose shape of provider events; we read only what we need.
export interface ProviderEvent {
  type: string;
  [key: string]: any;
}

export class TurnTracker {
  private speechStoppedAt: number | null = null;
  private interruptStartedAt: number | null = null;
  private assistantPlaying = false;
  private currentResponseId: string | null = null;
  private cancelledResponses = new Set<string>();
  private clearedResponses = new Set<string>();
  private caption = '';
  private ready = false;

  constructor(private readonly vadSilenceMs: number) {}

  handle(ev: ProviderEvent, now: number): Effect[] {
    switch (ev.type) {
      case 'session.created':
      case 'session.updated': {
        if (this.ready) return [];
        this.ready = true;
        return [{ kind: 'ready' }, { kind: 'state', state: 'listening' }];
      }

      case 'input_audio_buffer.speech_started': {
        const out: Effect[] = [{ kind: 'userSpeaking', speaking: true }];
        if (this.assistantPlaying) {
          this.interruptStartedAt = now;
          if (this.currentResponseId) this.cancelledResponses.add(this.currentResponseId);
          // Stop showing the cut-off caption immediately; the audio stops on the provider side.
          this.caption = '';
          out.push({ kind: 'caption', text: '' });
        }
        this.speechStoppedAt = null;
        return out;
      }

      case 'input_audio_buffer.speech_stopped': {
        this.speechStoppedAt = now;
        return [{ kind: 'userSpeaking', speaking: false }];
      }

      case 'response.created': {
        this.currentResponseId = ev.response?.id ?? null;
        this.caption = '';
        return [];
      }

      case 'response.output_audio_transcript.delta':
      case 'response.audio_transcript.delta': {
        if (ev.response_id && this.cancelledResponses.has(ev.response_id)) return []; // late event of an old turn
        if (ev.response_id && this.currentResponseId && ev.response_id !== this.currentResponseId) return [];
        this.caption += ev.delta ?? '';
        return [{ kind: 'caption', text: this.caption }];
      }

      case 'output_audio_buffer.started': {
        this.assistantPlaying = true;
        const out: Effect[] = [{ kind: 'state', state: 'speaking' }];
        if (this.speechStoppedAt !== null) {
          out.push({
            kind: 'metric',
            type: 'latency.reply',
            payload: { ms: Math.round(now - this.speechStoppedAt), vadSilenceMs: this.vadSilenceMs },
          });
          this.speechStoppedAt = null;
        }
        return out;
      }

      case 'output_audio_buffer.stopped': {
        this.assistantPlaying = false;
        return [{ kind: 'state', state: 'listening' }];
      }

      case 'output_audio_buffer.cleared': {
        this.assistantPlaying = false;
        const out: Effect[] = [{ kind: 'state', state: 'listening' }];
        if (this.currentResponseId) this.clearedResponses.add(this.currentResponseId);
        if (this.interruptStartedAt !== null) {
          out.push({ kind: 'metric', type: 'latency.interrupt', payload: { ms: Math.round(now - this.interruptStartedAt) } });
          out.push({ kind: 'event', type: 'turn.interrupted', payload: { responseId: this.currentResponseId } });
          this.interruptStartedAt = null;
        }
        return out;
      }

      case 'conversation.item.input_audio_transcription.completed': {
        return [{
          kind: 'message', role: 'user', content: (ev.transcript ?? '').trim(), interrupted: false,
          providerItemId: ev.item_id,
        }];
      }

      case 'conversation.item.input_audio_transcription.failed': {
        // Spec p.7: a missing transcript is marked as a gap.
        return [{ kind: 'event', type: 'transcript.gap', payload: { itemId: ev.item_id, reason: ev.error?.message ?? 'failed' } }];
      }

      case 'response.done': {
        const r = ev.response ?? {};
        const interrupted = r.status === 'cancelled' || this.cancelledResponses.has(r.id) || this.clearedResponses.has(r.id);
        const out: Effect[] = [];
        for (const item of r.output ?? []) {
          if (item.type !== 'message' || item.role !== 'assistant') continue;
          const text = (item.content ?? [])
            .map((c: any) => c.transcript ?? c.text ?? '')
            .join('')
            .trim();
          if (!text) continue;
          out.push({ kind: 'message', role: 'assistant', content: text, interrupted, providerItemId: item.id });
        }
        if (r.status === 'failed') {
          out.push({ kind: 'event', type: 'response.failed', payload: { details: r.status_details ?? null } });
        }
        if (r.id === this.currentResponseId) this.currentResponseId = null;
        return out;
      }

      case 'error': {
        return [{ kind: 'event', type: 'provider.error', payload: { code: ev.error?.code ?? null, message: ev.error?.message ?? null } }];
      }

      default:
        return [];
    }
  }
}
