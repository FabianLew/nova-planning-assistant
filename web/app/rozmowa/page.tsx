'use client';
import { useCallback, useEffect, useRef, useState } from 'react';
import { AppShell } from '@/components/AppShell';
import { Orb } from '@/components/Orb';
import type { StartedSession } from '@/lib/api';
import { VoiceSession, type TranscriptEntry } from '@/lib/realtime';
import { percentile } from '@/lib/stats';
import type { VoiceState } from '@/lib/turns';

export default function RozmowaPage() {
  const audioRef = useRef<HTMLAudioElement>(null);
  const voice = useRef<VoiceSession | null>(null);
  const [state, setState] = useState<VoiceState>('idle');
  const [userSpeaking, setUserSpeaking] = useState(false);
  const [muted, setMuted] = useState(false);
  const [caption, setCaption] = useState('');
  const [transcript, setTranscript] = useState<TranscriptEntry[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [session, setSession] = useState<StartedSession | null>(null);
  const [replyMs, setReplyMs] = useState<number[]>([]);
  const [interruptMs, setInterruptMs] = useState<number[]>([]);
  const [showMetrics, setShowMetrics] = useState(false);

  const live = state === 'connecting' || state === 'listening' || state === 'speaking' || state === 'disconnected';

  const start = useCallback(async () => {
    if (!audioRef.current) return;
    setError(null);
    setTranscript([]);
    setCaption('');
    setReplyMs([]);
    setInterruptMs([]);
    setMuted(false);
    const v = new VoiceSession(audioRef.current, {
      onState: setState,
      onUserSpeaking: setUserSpeaking,
      onCaption: setCaption,
      onTranscript: (t) => setTranscript((prev) => [...prev, t]),
      onMetric: (type, p) => {
        if (type === 'latency.reply') setReplyMs((prev) => [...prev, p.ms + p.vadSilenceMs]);
        else setInterruptMs((prev) => [...prev, p.ms]);
      },
      onError: setError,
      onSession: setSession,
    });
    voice.current = v;
    await v.start();
  }, []);

  const end = useCallback(async () => {
    await voice.current?.end('user');
    setUserSpeaking(false);
  }, []);

  const reconnect = useCallback(async () => {
    await voice.current?.end('network');
    await start();
  }, [start]);

  const toggleMute = useCallback(() => {
    setMuted((m) => {
      voice.current?.setMuted(!m);
      return !m;
    });
  }, []);

  // Release the microphone if the page goes away mid-session.
  useEffect(() => {
    const leave = () => void voice.current?.end('page_closed');
    window.addEventListener('pagehide', leave);
    return () => {
      window.removeEventListener('pagehide', leave);
      leave();
    };
  }, []);

  const lastAssistant = [...transcript].reverse().find((t) => t.role === 'assistant');
  const shownText = caption || lastAssistant?.content || '';
  const name = session?.displayName ?? 'NOVA';

  const statusLine =
    state === 'idle' || state === 'ended' ? 'Nie połączono'
    : state === 'connecting' ? 'Łączenie…'
    : state === 'disconnected' ? 'Rozłączono · mikrofon wstrzymany'
    : state === 'error' ? 'Błąd połączenia'
    : `Połączono · ${muted ? 'Mikrofon wyciszony' : 'Mikrofon włączony'}`;

  return (
    <AppShell>
      <h1>Rozmowa</h1>
      <p className="subtitle">{statusLine}</p>

      <Orb state={state} userSpeaking={userSpeaking} muted={muted} />

      <div className="card" style={{ minHeight: 96 }}>
        <div className="label">Ostatnia wypowiedź · {name}</div>
        <div style={{ fontSize: 19, lineHeight: 1.35 }}>
          {shownText ? `„${shownText}”` : <span className="hint">Tu pojawi się, co powiedziała {name}.</span>}
          {lastAssistant?.interrupted && !caption && <span className="hint"> · urwane</span>}
        </div>
      </div>

      {error && <p className="error" role="alert">{error}</p>}

      <div style={{ marginTop: 'auto', paddingTop: 16, display: 'flex', flexDirection: 'column', gap: 10 }}>
        {!live && (
          <button className="btn btn-primary" style={{ minHeight: 64, fontSize: 18 }} onClick={start}>
            Rozpocznij
          </button>
        )}
        {state === 'disconnected' && (
          <button className="btn btn-primary" onClick={reconnect}>Połącz ponownie</button>
        )}
        {live && (
          <div className="row">
            <button className={`btn ${muted ? 'btn-on' : ''}`} onClick={toggleMute} aria-pressed={muted}>
              {muted ? '🎙️ Włącz mikrofon' : '🔇 Wycisz mikrofon'}
            </button>
            <button className="btn btn-danger" onClick={end}>■ Zakończ sesję</button>
          </div>
        )}
        <button className="btn" style={{ minHeight: 40, fontSize: 14 }} onClick={() => setShowMetrics((s) => !s)}>
          {showMetrics ? 'Ukryj pomiary' : 'Pomiary M1'}
        </button>
        {showMetrics && <Metrics replyMs={replyMs} interruptMs={interruptMs} transcript={transcript} sessionId={session?.sessionId} />}
      </div>

      {/* Remote voice. playsInline keeps iOS from going fullscreen. */}
      <audio ref={audioRef} autoPlay playsInline />
    </AppShell>
  );
}

function Metrics({ replyMs, interruptMs, transcript, sessionId }: {
  replyMs: number[]; interruptMs: number[]; transcript: TranscriptEntry[]; sessionId?: string;
}) {
  const fmt = (v: number | null) => (v === null ? '–' : `${Math.round(v)} ms`);
  const r95 = percentile(replyMs, 95);
  const i95 = percentile(interruptMs, 95);
  return (
    <div className="card" style={{ fontSize: 14 }}>
      <div className="label">Odpowiedź (koniec mowy → dźwięk) · cel p95 ≤ 2000 ms</div>
      <div>Próby: {replyMs.length}/20 · p50 {fmt(percentile(replyMs, 50))} · p95 <b style={{ color: r95 === null ? undefined : r95 <= 2000 ? 'var(--ok)' : 'var(--warn)' }}>{fmt(r95)}</b>{r95 !== null && (r95 <= 2000 ? ' ✓' : ' ✗')}</div>
      <div className="label" style={{ marginTop: 10 }}>Wejście w słowo (wtrącenie → cisza) · cel p95 ≤ 400 ms</div>
      <div>Próby: {interruptMs.length}/20 · p50 {fmt(percentile(interruptMs, 50))} · p95 <b style={{ color: i95 === null ? undefined : i95 <= 400 ? 'var(--ok)' : 'var(--warn)' }}>{fmt(i95)}</b>{i95 !== null && (i95 <= 400 ? ' ✓' : ' ✗')}</div>
      <div className="label" style={{ marginTop: 10 }}>Transkrypcja</div>
      <div style={{ maxHeight: 200, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 4 }}>
        {transcript.length === 0 && <span className="hint">Brak wypowiedzi.</span>}
        {transcript.map((t) => (
          <div key={t.id + t.role}>
            <b>{t.role === 'user' ? 'Ty' : 'NOVA'}:</b> {t.content}{t.interrupted && <span className="hint"> (urwane)</span>}
          </div>
        ))}
      </div>
      {sessionId && <p className="hint" style={{ marginBottom: 0 }}>Sesja {sessionId.slice(0, 8)} · pełne dane: /api/sessions/{sessionId}/metrics</p>}
    </div>
  );
}
