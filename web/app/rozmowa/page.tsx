'use client';
import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { AppShell } from '@/components/AppShell';
import { ApprovalCard, decisionNote } from '@/components/ApprovalCard';
import { Orb } from '@/components/Orb';
import { Api } from '@/lib/api';
import { useLive } from '@/lib/hooks';
import type { StartedSession, Take, TakeEvent } from '@/lib/api';
import { VoiceSession, type RecordEntry, type TranscriptEntry } from '@/lib/realtime';
import { offsetLabel, TakeRecorder } from '@/lib/takes';
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
  const [toolNote, setToolNote] = useState<string | null>(null);
  const [take, setTake] = useState<Take | null>(null);
  const recorder = useRef<TakeRecorder | null>(null);
  if (!recorder.current && typeof window !== 'undefined') recorder.current = new TakeRecorder(setTake);
  const { data: day } = useLive(() => Api.today(), ['approval.requested', 'approval.decided', 'plan.updated']);

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
      onTool: (call, status) => setToolNote(`${TOOL_LABEL[call.name] ?? call.name} · ${STATUS_LABEL[status] ?? status}`),
      onRecord: (e: RecordEntry) => {
        const r = recorder.current;
        if (!r) return;
        if (e.type === 'connection_lost') r.connectionLost(e.text ?? 'network');
        else if (e.type === 'connection_restored') r.connectionRestored();
        else if (e.type === 'tool') {
          const [name, status] = (e.text ?? '').split(' · ');
          void r.log('tool', 'assistant', `${TOOL_LABEL[name] ?? name} · ${STATUS_LABEL[status] ?? status}`, e.payload);
        } else void r.log(e.type, e.speaker, e.text, e.payload);
      },
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
    recorder.current?.connectionRestored();
  }, [start]);

  const toggleMute = useCallback(() => {
    setMuted((m) => {
      voice.current?.setMuted(!m);
      return !m;
    });
  }, []);

  useEffect(() => {
    void recorder.current?.restore();
  }, []);

  const privacy = useCallback(() => {
    recorder.current?.privacy();
    voice.current?.setMuted(true);
    setMuted(true);
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

      {toolNote && live && <p className="hint" style={{ margin: '8px 2px 0' }}>⚙︎ {toolNote}</p>}

      {day?.approvals.map((a) => (
        <div key={a.id} style={{ marginTop: 12 }}>
          <ApprovalCard approval={a} tasks={day.tasks} timeZone={day.timeZone}
                        onDecided={(outcome, ap) => {
                          const note = decisionNote(outcome, ap);
                          void recorder.current?.log('decision', 'creator', note, { approvalId: ap.id, outcome });
                          voice.current?.notify(note);
                        }} />
        </div>
      ))}

      {error && <p className="error" role="alert">{error}</p>}

      {recorder.current && (
        <RecordPanel take={take} recorder={recorder.current} sessionId={session?.sessionId ?? null} onPrivacy={privacy}
                     onError={setError} />
      )}

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

const TOOL_LABEL: Record<string, string> = {
  get_current_context: 'Sprawdzam dzień',
  get_calendar_events: 'Sprawdzam kalendarz',
  create_task: 'Dodaję zadanie',
  propose_daily_plan: 'Proponuję plan',
  update_daily_plan: 'Proponuję zmianę',
  set_task_status: 'Oznaczam zadanie',
  add_busy_block: 'Zapisuję zajętość',
  save_memory: 'Zapamiętuję',
  search_memory: 'Szukam w pamięci',
  get_experiment_status: 'Sprawdzam eksperyment',
  propose_activity: 'Proponuję aktywność',
  record_expense: 'Księguję wydatek',
};
const STATUS_LABEL: Record<string, string> = {
  ok: 'gotowe', pending_approval: 'czeka na Twoją zgodę', skipped: 'pominięte', error: 'błąd',
};

/** Take controls (spec p.7): REC, "Do montażu +", pause, privacy. */
function RecordPanel({ take, recorder, sessionId, onPrivacy, onError }: {
  take: Take | null; recorder: TakeRecorder; sessionId: string | null; onPrivacy: () => void; onError: (m: string) => void;
}) {
  const [now, setNow] = useState(() => Date.now());
  const [info, setInfo] = useState(false);
  const [lastMarker, setLastMarker] = useState<TakeEvent | null>(null);
  const [describing, setDescribing] = useState(false);
  const [note, setNote] = useState('');
  const [flash, setFlash] = useState<string | null>(null);

  useEffect(() => {
    if (!take) return;
    const t = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(t);
  }, [take]);

  useEffect(() => {
    if (!lastMarker || describing) return;
    const t = setTimeout(() => setLastMarker(null), 10000);
    return () => clearTimeout(t);
  }, [lastMarker, describing]);

  const startTake = async () => {
    try {
      await recorder.start(undefined, sessionId);
    } catch (e) {
      onError(e instanceof Error ? e.message : 'Nie udało się rozpocząć ujęcia');
    }
  };

  const mark = async () => {
    const at = take ? offsetLabel(Math.max(0, Date.now() - Date.parse(take.startedAt))) : '';
    setFlash(`Znacznik ${at}`);
    setTimeout(() => setFlash(null), 1500);
    const e = await recorder.marker();
    if (e) setLastMarker(e);
  };

  const saveNote = () => {
    if (lastMarker && note.trim()) recorder.describe(lastMarker, note.trim());
    setDescribing(false);
    setNote('');
    setLastMarker(null);
  };

  if (!take) {
    return (
      <div className="card" style={{ marginTop: 12 }}>
        <div className="row" style={{ alignItems: 'center' }}>
          <button className="btn" onClick={startTake} style={{ flex: 2 }}>
            <span style={{ color: 'var(--rec)' }}>●</span> Zapisuj ujęcie
          </button>
          <button className="btn btn-sm" style={{ flex: 0, minWidth: 44 }} onClick={() => setInfo((i) => !i)} aria-label="Co jest zapisywane">ⓘ</button>
        </div>
        {info && (
          <p className="hint" style={{ marginBottom: 0 }}>
            Zapisuje się: transkrypcja obu stron, wejścia w słowo, decyzje z kart, użyte narzędzia, znaczniki „Do montażu”
            i przerwy w sieci, każde z czasem od punktu zerowego ujęcia. Dźwięk nie jest zapisywany. Podczas pauzy nic
            nie trafia do zapisu. Wpisy oznaczone jako prywatne nie trafiają do eksportu. Transkrypty są usuwane po 30 dniach.
          </p>
        )}
        <p className="hint" style={{ marginBottom: 0 }}><Link href="/ujecia/" style={{ color: 'var(--cyan)' }}>Oś ujęć i eksport →</Link></p>
      </div>
    );
  }

  const paused = take.status === 'paused';
  const elapsed = Math.max(0, now - Date.parse(take.startedAt));
  return (
    <div className="card" style={{ marginTop: 12, borderColor: paused ? 'var(--warn)' : 'var(--rec)' }} data-testid="rec-panel">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
        <span style={{ fontWeight: 700, color: paused ? 'var(--warn)' : 'var(--rec)', fontVariantNumeric: 'tabular-nums' }}>
          {paused ? '❚❚ PAUZA' : '● REC'} {offsetLabel(elapsed).slice(0, 5)}
        </span>
        <span className="hint">Ujęcie {take.number}{flash ? ` · ${flash}` : ''}</span>
      </div>
      <button className="btn btn-primary" style={{ width: '100%', minHeight: 64, fontSize: 18 }} onClick={mark} disabled={paused}>
        Do montażu +
      </button>
      {lastMarker && !describing && (
        <button className="btn btn-sm" style={{ width: '100%', marginTop: 8 }} onClick={() => setDescribing(true)}>✎ Opisz ostatni znacznik</button>
      )}
      {describing && (
        <div className="row" style={{ marginTop: 8 }}>
          <input autoFocus value={note} onChange={(e) => setNote(e.target.value)} placeholder="np. dobra reakcja na odmowę"
                 onKeyDown={(e) => e.key === 'Enter' && saveNote()} style={{ flex: 3 }} />
          <button className="btn btn-sm" onClick={saveNote}>Zapisz</button>
        </div>
      )}
      <div className="row" style={{ marginTop: 8 }}>
        {paused
          ? <button className="btn btn-sm btn-on" onClick={() => recorder.resume()}>▶ Wznów zapis</button>
          : <button className="btn btn-sm" onClick={() => recorder.pause()}>❚❚ Pauza zapisu</button>}
        <button className="btn btn-sm" onClick={onPrivacy} disabled={paused}>🔒 Prywatność</button>
      </div>
      <button className="btn btn-sm btn-danger" style={{ width: '100%', marginTop: 8 }} onClick={() => recorder.stop()}>■ Koniec ujęcia</button>
      {paused && <p className="hint" style={{ marginBottom: 0 }}>Rozmowa może trwać, ale nic nie trafia do zapisu.</p>}
    </div>
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
