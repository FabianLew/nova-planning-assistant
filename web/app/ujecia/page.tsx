'use client';
import { useEffect, useState } from 'react';
import { AppShell } from '@/components/AppShell';
import { Api, type Take, type TakeEvent } from '@/lib/api';
import { useLive } from '@/lib/hooks';
import { offsetLabel, SPEAKER_LABEL, TYPE_LABEL } from '@/lib/takes';

function todayIn(tz = 'Europe/Warsaw') {
  return new Intl.DateTimeFormat('en-CA', { timeZone: tz }).format(new Date());
}

/** Oś ujęć (spec p.7): the day's takes as a timeline, markers to edit, private entries, JSON/CSV export. */
export default function UjeciaPage() {
  const [date, setDate] = useState(todayIn());
  const { data: takes, error } = useLive(() => Api.takes(date), ['take.changed'], [date]);
  const [openId, setOpenId] = useState<string | null>(null);

  useEffect(() => {
    if (takes && takes.length && !openId) setOpenId(takes[takes.length - 1].id);
  }, [takes, openId]);

  const exportUrl = (format: 'json' | 'csv') => `/api/takes/export?date=${date}&format=${format}`;

  return (
    <AppShell>
      <h1>Oś ujęć</h1>
      <p className="subtitle">Czas od punktu zerowego każdego ujęcia. Prywatne wpisy nie trafiają do eksportu.</p>

      <label className="field">
        <span>Dzień</span>
        <input type="date" value={date} onChange={(e) => { setDate(e.target.value); setOpenId(null); }} />
      </label>

      <div className="row">
        <a className="btn btn-sm" href={exportUrl('csv')} download style={linkBtn}>⬇ CSV</a>
        <a className="btn btn-sm" href={exportUrl('json')} download style={linkBtn}>⬇ JSON</a>
      </div>

      {error && <p className="error">{error}</p>}
      {takes && takes.length === 0 && <p className="hint">Brak ujęć tego dnia. Włącz „Zapisuj ujęcie” na ekranie Rozmowa.</p>}

      <div className="list">
        {takes?.map((t) => (
          <TakeCard key={t.id} take={t} open={openId === t.id} onToggle={() => setOpenId(openId === t.id ? null : t.id)} />
        ))}
      </div>
    </AppShell>
  );
}

const linkBtn: React.CSSProperties = { display: 'flex', alignItems: 'center', justifyContent: 'center', textDecoration: 'none' };

function TakeCard({ take, open, onToggle }: { take: Take; open: boolean; onToggle: () => void }) {
  const { data, reload } = useLive(() => (open ? Api.take(take.id) : Promise.resolve(null)), ['take.changed'], [take.id, open]);
  const events = data?.events ?? [];
  const markers = events.filter((e) => e.type === 'marker').length;
  const time = new Date(take.startedAt).toLocaleTimeString('pl-PL', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  const status = take.status === 'recording' ? '● nagrywa' : take.status === 'paused' ? '❚❚ pauza' : 'zamknięte';

  return (
    <div className="card" data-testid="take-card">
      <button onClick={onToggle} style={{ all: 'unset', cursor: 'pointer', display: 'block', width: '100%' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between' }}>
          <b>Ujęcie {take.number}{take.label ? ` · ${take.label}` : ''}</b>
          <span className="hint">{status}</span>
        </div>
        <div className="hint">Start {time}{open ? ` · ${events.length} wpisów · ${markers} znaczników` : ''}</div>
      </button>
      {open && (
        <div style={{ marginTop: 10, display: 'flex', flexDirection: 'column', gap: 6 }}>
          {events.map((e) => <EventRow key={e.id} takeId={take.id} event={e} onSaved={reload} />)}
        </div>
      )}
    </div>
  );
}

function EventRow({ takeId, event, onSaved }: { takeId: string; event: TakeEvent; onSaved: () => void }) {
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState(event.text ?? '');
  const [busy, setBusy] = useState(false);
  const isMarker = event.type === 'marker';
  const who = event.speaker && event.speaker !== 'system' ? SPEAKER_LABEL[event.speaker] ?? event.speaker.toUpperCase() : (TYPE_LABEL[event.type] ?? event.type).toUpperCase();
  const label = event.type === 'utterance' || !event.speaker || event.speaker === 'system' ? '' : `${TYPE_LABEL[event.type] ?? event.type}${event.text ? ': ' : ''}`;

  const save = async (patch: { text?: string; isPrivate?: boolean }) => {
    setBusy(true);
    try {
      await Api.editTakeEvent(takeId, event.id, patch);
      onSaved();
    } finally {
      setBusy(false);
      setEditing(false);
    }
  };

  return (
    <div style={{ fontSize: 14, opacity: event.isPrivate ? 0.5 : 1, borderLeft: `3px solid ${isMarker ? 'var(--cyan)' : 'var(--line)'}`, paddingLeft: 8 }}
         data-testid={`event-${event.type}`}>
      <div>
        <span style={{ fontVariantNumeric: 'tabular-nums', color: 'var(--muted)' }}>{offsetLabel(event.offsetMs)}</span>
        {' · '}<b>{who}</b>{' · '}
        {event.type === 'utterance' ? `„${event.text ?? ''}”` : `${label}${event.text ?? (label ? '' : '–')}`}
        {event.payload?.interrupted && <span className="hint"> (urwane)</span>}
        {event.isPrivate && <span className="hint"> · prywatne</span>}
      </div>
      {editing ? (
        <div className="row" style={{ marginTop: 4 }}>
          <input value={text} onChange={(e) => setText(e.target.value)} style={{ flex: 3 }} autoFocus />
          <button className="btn btn-sm" disabled={busy} onClick={() => save({ text })}>Zapisz</button>
        </div>
      ) : (
        <div style={{ display: 'flex', gap: 12, marginTop: 2 }}>
          {isMarker && <button className="linkish" onClick={() => setEditing(true)}>✎ opis</button>}
          {!['sync', 'pause', 'resume', 'stop', 'privacy'].includes(event.type) && (
            <button className="linkish" disabled={busy} onClick={() => save({ isPrivate: !event.isPrivate })}>
              {event.isPrivate ? 'odkryj' : '🔒 prywatne'}
            </button>
          )}
        </div>
      )}
    </div>
  );
}
