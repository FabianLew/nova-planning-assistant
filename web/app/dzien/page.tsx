'use client';
import { useState } from 'react';
import { AppShell } from '@/components/AppShell';
import { ApprovalCard } from '@/components/ApprovalCard';
import { Api, type Task } from '@/lib/api';
import { hhmm, newKey, pln, useLive } from '@/lib/hooks';

const STATUS: Record<Task['status'], string> = {
  proposed: 'propozycja', planned: 'w planie', done: 'zrobione', skipped: 'pominięte', refused: 'odmowa', cancelled: 'anulowane',
};

export default function MyDayPage() {
  const { data: day, error, reload } = useLive(() => Api.today(),
    ['plan.updated', 'approval.requested', 'approval.decided', 'task.created', 'task.status', 'busy.changed']);
  const [adding, setAdding] = useState<'task' | 'busy' | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);

  if (!day) {
    return <AppShell><h1>Mój dzień</h1><p className="hint">{error ?? 'Wczytywanie…'}</p></AppShell>;
  }
  const tz = day.timeZone;
  const active = day.tasks.filter((t) => t.status !== 'proposed');
  const doneCount = active.filter((t) => t.status === 'done').length;
  const weekday = new Date(day.date + 'T12:00:00').toLocaleDateString('pl-PL', { weekday: 'long', day: 'numeric', month: 'long' });

  async function act(fn: () => Promise<unknown>) {
    setMsg(null);
    try {
      await fn();
      await reload();
    } catch (e) {
      setMsg((e as Error).message);
    }
  }

  return (
    <AppShell>
      <h1>Mój dzień</h1>
      <p className="subtitle">
        {weekday.charAt(0).toUpperCase() + weekday.slice(1)} · {active.length ? `${doneCount} z ${active.length} zadań` : 'brak zadań'}
        {day.plan && ` · plan ${day.plan.status === 'approved' ? 'zatwierdzony' : 'szkic'} v${day.plan.version}`}
      </p>

      {day.approvals.map((a) => (
        <div key={a.id} style={{ marginTop: 12 }}>
          <ApprovalCard approval={a} tasks={day.tasks} timeZone={tz} onDecided={() => void reload()} />
        </div>
      ))}

      <div className="section-title">Plan dnia</div>
      <div className="list">
        {active.length === 0 && <p className="hint">Nic jeszcze nie zaplanowano. Poproś NOVĘ o plan albo dodaj zadanie.</p>}
        {active.map((t) => (
          <div key={t.id} className="card" style={{ opacity: t.status === 'planned' ? 1 : 0.75 }}>
            <div style={{ display: 'flex', gap: 12, alignItems: 'baseline' }}>
              <b style={{ fontSize: 18, minWidth: 52 }}>{hhmm(t.startAt, tz)}</b>
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: 17, fontWeight: 600 }}>{t.title}</div>
                <div className="hint">
                  {t.durationMin} min{t.costGrosze > 0 ? ` · ${pln(t.costGrosze)}` : ''} · {t.source === 'assistant' ? 'NOVA' : 'Ty'}
                  {t.refusalReason ? ` · powód: ${t.refusalReason}` : ''}
                </div>
              </div>
              <span className={`chip chip-${t.status}`}>{STATUS[t.status]}</span>
            </div>
            {t.status === 'planned' && editing !== t.id && (
              <div className="row" style={{ marginTop: 10 }}>
                <button className="btn btn-sm" onClick={() => act(() => Api.taskStatus(t.id, 'done'))}>Zrobione</button>
                <button className="btn btn-sm" onClick={() => act(() => Api.taskStatus(t.id, 'skipped'))}>Pomiń</button>
                <button className="btn btn-sm" onClick={() => {
                  const reason = window.prompt('Powód odmowy (opcjonalnie)') ?? undefined;
                  void act(() => Api.taskStatus(t.id, 'refused', reason || undefined));
                }}>Odmów</button>
                <button className="btn btn-sm" onClick={() => setEditing(t.id)}>Edytuj</button>
              </div>
            )}
            {editing === t.id && day.plan && (
              <TaskEditor task={t} tz={tz} onCancel={() => setEditing(null)} onSave={(title, startTime, durationMin) =>
                act(async () => {
                  await Api.editTask(t.id, { title, startTime, durationMin, expectedPlanVersion: day.plan!.version });
                  setEditing(null);
                })} />
            )}
            {['done', 'skipped', 'refused'].includes(t.status) && (
              <button className="btn btn-sm" style={{ marginTop: 8 }} onClick={() => act(() => Api.taskStatus(t.id, 'planned'))}>Cofnij</button>
            )}
          </div>
        ))}
      </div>

      <div className="section-title">Zajętość</div>
      <div className="list">
        {day.busy.length === 0 && <p className="hint">Brak wpisanych bloków. NOVA planuje wokół nich.</p>}
        {day.busy.map((b) => (
          <div key={b.id} className="card" style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <b>{hhmm(b.startsAt, tz)}–{hhmm(b.endsAt, tz)}</b>
            <span style={{ flex: 1 }}>{b.label}</span>
            <button className="btn btn-sm" aria-label={`Usuń ${b.label}`} onClick={() => act(() => Api.deleteBusy(b.id))}>Usuń</button>
          </div>
        ))}
      </div>

      {msg && <p className="error">{msg}</p>}

      {adding === null ? (
        <div className="row" style={{ marginTop: 16 }}>
          <button className="btn" onClick={() => setAdding('task')}>+ Zadanie</button>
          <button className="btn" onClick={() => setAdding('busy')}>+ Zajęty czas</button>
        </div>
      ) : (
        <AddForm kind={adding} onCancel={() => setAdding(null)} onSubmit={(f) => act(async () => {
          if (adding === 'task') await Api.addTask(day.date, { title: f.label, startTime: f.start || undefined, durationMin: f.duration, key: newKey() });
          else await Api.addBusy(day.date, { startTime: f.start, endTime: f.end, label: f.label });
          setAdding(null);
        })} />
      )}
    </AppShell>
  );
}

function TaskEditor({ task, tz, onSave, onCancel }: {
  task: Task; tz: string; onSave: (title: string, start: string | undefined, duration: number) => void; onCancel: () => void;
}) {
  const [title, setTitle] = useState(task.title);
  const [start, setStart] = useState(task.startAt ? hhmm(task.startAt, tz) : '');
  const [duration, setDuration] = useState(task.durationMin);
  return (
    <div style={{ marginTop: 10 }}>
      <input value={title} onChange={(e) => setTitle(e.target.value)} aria-label="Nazwa" />
      <div className="row" style={{ marginTop: 8 }}>
        <input type="time" value={start} onChange={(e) => setStart(e.target.value)} aria-label="Godzina" />
        <input type="number" min={5} max={720} value={duration} onChange={(e) => setDuration(Number(e.target.value))} aria-label="Minuty" />
      </div>
      <div className="row" style={{ marginTop: 8 }}>
        <button className="btn btn-sm btn-primary" onClick={() => onSave(title, start || undefined, duration)}>Zapisz</button>
        <button className="btn btn-sm" onClick={onCancel}>Anuluj</button>
      </div>
    </div>
  );
}

function AddForm({ kind, onSubmit, onCancel }: {
  kind: 'task' | 'busy';
  onSubmit: (f: { label: string; start: string; end: string; duration: number }) => void;
  onCancel: () => void;
}) {
  const [label, setLabel] = useState('');
  const [start, setStart] = useState('');
  const [end, setEnd] = useState('');
  const [duration, setDuration] = useState(30);
  const valid = label.trim() && (kind === 'task' || (start && end));
  return (
    <div className="card" style={{ marginTop: 16 }}>
      <label className="field"><span>{kind === 'task' ? 'Zadanie' : 'Czym jesteś zajęty'}</span>
        <input value={label} onChange={(e) => setLabel(e.target.value)} placeholder={kind === 'task' ? 'np. Montaż vloga' : 'np. Montaż'} />
      </label>
      <div className="row">
        <label className="field"><span>Od</span><input type="time" value={start} onChange={(e) => setStart(e.target.value)} /></label>
        {kind === 'busy'
          ? <label className="field"><span>Do</span><input type="time" value={end} onChange={(e) => setEnd(e.target.value)} /></label>
          : <label className="field"><span>Minuty</span><input type="number" min={5} max={720} value={duration} onChange={(e) => setDuration(Number(e.target.value))} /></label>}
      </div>
      <div className="row">
        <button className="btn btn-primary" disabled={!valid} onClick={() => onSubmit({ label: label.trim(), start, end, duration })}>Dodaj</button>
        <button className="btn" onClick={onCancel}>Anuluj</button>
      </div>
    </div>
  );
}
