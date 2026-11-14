/**
 * Browser side of a NOVA voice session: microphone -> WebRTC -> OpenAI Realtime, remote audio -> <audio>.
 * The server only hands out a short-lived credential and records the transcript and timeline.
 */
import { Api, type StartedSession } from './api';
import { subscribe } from './events';
import { Outbox } from './outbox';
import { TurnTracker, type Effect, type ToolCall, type VoiceState } from './turns';

export interface TranscriptEntry {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  interrupted: boolean;
  at: number;
}

export interface VoiceCallbacks {
  onState(state: VoiceState): void;
  onUserSpeaking(speaking: boolean): void;
  onCaption(text: string): void;
  onTranscript(entry: TranscriptEntry): void;
  onMetric(type: 'latency.reply' | 'latency.interrupt', payload: Record<string, number>): void;
  onError(message: string): void;
  onSession(session: StartedSession): void;
  onTool?(call: ToolCall, status: string): void;
}

export class VoiceSession {
  private pc: RTCPeerConnection | null = null;
  private dc: RTCDataChannel | null = null;
  private mic: MediaStream | null = null;
  private tracker: TurnTracker | null = null;
  private session: StartedSession | null = null;
  private outbox = new Outbox((e) => console.warn('NOVA: write rejected', e));
  private muted = false;
  private state: VoiceState = 'idle';
  private limitTimer: ReturnType<typeof setTimeout> | null = null;
  private wakeLock: { release(): Promise<void> } | null = null;
  private closed = false;
  private unsubscribe: (() => void) | null = null;
  private refreshTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(private readonly audio: HTMLAudioElement, private readonly cb: VoiceCallbacks) {}

  get sessionId() {
    return this.session?.sessionId ?? null;
  }

  /** Must be called from a tap: iOS only grants microphone and audio playback inside a user gesture. */
  async start(): Promise<void> {
    this.setState('connecting');
    // Unlock playback on iOS while we are still inside the gesture.
    this.audio.muted = false;
    void this.audio.play().catch(() => {});

    let mic: MediaStream;
    try {
      mic = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      });
    } catch (e) {
      this.setState('error');
      this.cb.onError(micErrorMessage(e));
      return;
    }
    this.mic = mic;

    try {
      const session = await Api.startSession();
      this.session = session;
      this.tracker = new TurnTracker(session.turnDetection === 'server_vad' ? session.vadSilenceMs : 0);
      this.cb.onSession(session);
      await this.connect(session, mic);
      this.limitTimer = setTimeout(() => void this.end('time_limit'), session.maxDurationSeconds * 1000);
      void this.keepScreenOn();
      window.addEventListener('offline', this.onOffline);
      window.addEventListener('online', this.onOnline);
      document.addEventListener('visibilitychange', this.onVisibility);
      // Memory or plan edited elsewhere (Pamięć screen, approval card): refresh the instruction before the
      // next answer (spec p.4).
      this.unsubscribe = subscribe((e) => {
        if (['memory.changed', 'memory.deleted', 'plan.updated', 'busy.changed'].includes(e.type)) this.scheduleRefresh();
      });
    } catch (e) {
      this.cleanupMedia();
      this.setState('error');
      this.cb.onError(e instanceof Error ? e.message : 'Nie udało się połączyć');
      if (this.session) void Api.endSession(this.session.sessionId, 'connect_failed').catch(() => {});
    }
  }

  private async connect(session: StartedSession, mic: MediaStream) {
    const pc = new RTCPeerConnection();
    this.pc = pc;

    pc.ontrack = (e) => {
      this.audio.srcObject = e.streams[0];
      void this.audio.play().catch(() => {});
    };
    pc.onconnectionstatechange = () => this.onConnectionState(pc.connectionState);

    pc.addTrack(mic.getAudioTracks()[0], mic);

    const dc = pc.createDataChannel('oai-events');
    this.dc = dc;
    dc.onmessage = (m) => {
      try {
        this.apply(this.tracker!.handle(JSON.parse(m.data), performance.now()));
      } catch (err) {
        console.warn('NOVA: bad provider event', err);
      }
    };

    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);

    const res = await fetch(session.rtcUrl, {
      method: 'POST',
      body: offer.sdp,
      headers: { Authorization: `Bearer ${session.clientSecret}`, 'Content-Type': 'application/sdp' },
    });
    if (!res.ok) {
      throw new Error(`Dostawca głosu odrzucił połączenie (${res.status})`);
    }
    const callId = res.headers.get('Location')?.split('/').pop() ?? null;
    await pc.setRemoteDescription({ type: 'answer', sdp: await res.text() });
    this.outbox.push(() => Api.connected(session.sessionId, callId));
  }

  private apply(effects: Effect[]) {
    const id = this.session?.sessionId;
    for (const fx of effects) {
      switch (fx.kind) {
        case 'state':
          if (this.state !== 'disconnected') this.setState(fx.state);
          break;
        case 'ready':
          break;
        case 'userSpeaking':
          this.cb.onUserSpeaking(fx.speaking);
          break;
        case 'caption':
          this.cb.onCaption(fx.text);
          break;
        case 'message':
          this.cb.onTranscript({ id: fx.providerItemId, role: fx.role, content: fx.content, interrupted: fx.interrupted, at: Date.now() });
          if (id) {
            const m = { role: fx.role, content: fx.content, interrupted: fx.interrupted, providerItemId: fx.providerItemId };
            this.outbox.push(() => Api.message(id, m));
          }
          break;
        case 'metric':
          this.cb.onMetric(fx.type, fx.payload);
          if (id) this.outbox.push(() => Api.event(id, fx.type, fx.payload));
          break;
        case 'event':
          if (id) this.outbox.push(() => Api.event(id, fx.type, fx.payload));
          break;
        case 'toolCalls':
          void this.runTools(fx.calls);
          break;
      }
    }
  }

  /**
   * The phone only relays: the server validates and executes the call, and its output goes back to the model
   * in the same turn (by call id). Network errors are reported to the model as errors, never as success.
   */
  private async runTools(calls: ToolCall[]) {
    const id = this.session?.sessionId;
    if (!id) return;
    for (const call of calls) {
      let output: unknown;
      let status = 'error';
      try {
        const res = await Api.toolCall(id, call.callId, call.name, call.arguments);
        output = res.output;
        status = res.status;
      } catch (e) {
        output = { status: 'error', error: 'unreachable', message: 'Serwer nie odpowiedział. Nic nie zostało zmienione albo stan jest nieznany; sprawdź get_current_context.' };
      }
      this.cb.onTool?.(call, status);
      this.send({ type: 'conversation.item.create', item: { type: 'function_call_output', call_id: call.callId, output: JSON.stringify(output) } });
    }
    this.send({ type: 'response.create' });
  }

  /** Tells the model what the creator decided on an approval card, then lets it react. */
  notify(text: string) {
    if (this.closed || this.state === 'disconnected') return;
    this.send({ type: 'conversation.item.create', item: { type: 'message', role: 'system', content: [{ type: 'input_text', text }] } });
    this.send({ type: 'response.create' });
  }

  private scheduleRefresh() {
    if (this.refreshTimer) clearTimeout(this.refreshTimer);
    this.refreshTimer = setTimeout(async () => {
      const id = this.session?.sessionId;
      if (!id || this.closed) return;
      try {
        const { instructions } = await Api.instructions(id);
        this.send({ type: 'session.update', session: { type: 'realtime', instructions } });
      } catch { /* next change will retry */ }
    }, 400);
  }

  private send(event: Record<string, unknown>) {
    if (this.dc?.readyState === 'open') this.dc.send(JSON.stringify(event));
  }

  setMuted(muted: boolean) {
    this.muted = muted;
    this.mic?.getAudioTracks().forEach((t) => (t.enabled = !muted && this.state !== 'disconnected'));
    this.logEvent(muted ? 'mic.muted' : 'mic.unmuted', {});
  }

  async end(reason = 'user'): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    const id = this.session?.sessionId;
    this.cleanupMedia();
    this.setState('ended');
    if (id) this.outbox.push(() => Api.endSession(id, reason));
  }

  private onConnectionState(s: RTCPeerConnectionState) {
    if (this.closed) return;
    if (s === 'disconnected' || s === 'failed') {
      this.markDisconnected(s);
    } else if (s === 'connected' && this.state === 'disconnected') {
      this.setState('listening');
      this.mic?.getAudioTracks().forEach((t) => (t.enabled = !this.muted));
      this.logEvent('connection.restored', {});
    }
  }

  private markDisconnected(reason: string) {
    if (this.state === 'disconnected') return;
    // Spec p.3: show "Rozłączono", stop sending audio, record the gap on the timeline.
    this.mic?.getAudioTracks().forEach((t) => (t.enabled = false));
    this.setState('disconnected');
    this.logEvent('connection.lost', { reason });
  }

  private onOffline = () => this.markDisconnected('offline');
  private onOnline = () => void this.outbox.drain();
  private onVisibility = () => {
    if (document.visibilityState === 'visible' && !this.closed) void this.keepScreenOn();
    this.logEvent(document.visibilityState === 'visible' ? 'app.visible' : 'app.hidden', {});
  };

  private logEvent(type: string, payload: Record<string, unknown>) {
    const id = this.session?.sessionId;
    if (id) this.outbox.push(() => Api.event(id, type, payload));
  }

  private async keepScreenOn() {
    try {
      const nav = navigator as Navigator & { wakeLock?: { request(type: 'screen'): Promise<{ release(): Promise<void> }> } };
      this.wakeLock = (await nav.wakeLock?.request('screen')) ?? null;
    } catch { /* not supported or denied: the creator keeps the screen on */ }
  }

  private cleanupMedia() {
    if (this.limitTimer) clearTimeout(this.limitTimer);
    if (this.refreshTimer) clearTimeout(this.refreshTimer);
    this.unsubscribe?.();
    this.unsubscribe = null;
    window.removeEventListener('offline', this.onOffline);
    window.removeEventListener('online', this.onOnline);
    document.removeEventListener('visibilitychange', this.onVisibility);
    this.dc?.close();
    this.pc?.getSenders().forEach((s) => s.track?.stop());
    this.pc?.close();
    this.mic?.getTracks().forEach((t) => t.stop()); // releases the microphone (iOS indicator goes off)
    this.audio.srcObject = null;
    void this.wakeLock?.release().catch(() => {});
    this.wakeLock = null;
    this.pc = null;
    this.dc = null;
    this.mic = null;
  }

  private setState(s: VoiceState) {
    this.state = s;
    this.cb.onState(s);
  }
}

function micErrorMessage(e: unknown): string {
  const name = e instanceof DOMException ? e.name : '';
  if (name === 'NotAllowedError') {
    return 'Brak dostępu do mikrofonu. Na iPhonie: Ustawienia → Safari → Mikrofon → Pozwól, a potem spróbuj ponownie.';
  }
  if (name === 'NotFoundError') return 'Nie znaleziono mikrofonu.';
  return 'Nie udało się włączyć mikrofonu.';
}
