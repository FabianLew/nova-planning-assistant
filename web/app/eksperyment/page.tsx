'use client';
import Link from 'next/link';
import { useState } from 'react';
import { AppShell } from '@/components/AppShell';
import { Api, type ExperimentConfig, type ExperimentStatus, type Report } from '@/lib/api';
import { newKey, pln, useLive } from '@/lib/hooks';

/** Experiment screen (spec p.6, mockup p.12): rules, budget, refusals, evening review, weekly report. */
export default function ExperimentPage() {
  const { data, error, reload } = useLive(async () => ({ st: (await Api.experiment()) ?? null }),
    ['experiment.changed', 'budget.changed', 'review.saved', 'task.status', 'plan.updated']);
  const [view, setView] = useState<'main' | 'rules' | 'report' | 'setup'>('main');
  const [msg, setMsg] = useState<string | null>(null);

  async function act(fn: () => Promise<unknown>) {
    setMsg(null);
    try {
      await fn();
      await reload();
    } catch (e) {
      setMsg((e as Error).message);
    }
  }

  if (data === null && !error) {
    return <AppShell><h1>Eksperyment</h1><p className="hint">Wczytywanie…</p></AppShell>;
  }
  const st = data?.st ?? undefined;
  const finished = !st || ['completed', 'ended_early'].includes(st.experiment.status);

  if (!st || view === 'setup' || (st.experiment.status === 'draft' && view === 'rules')) {
    return (
      <AppShell>
        <h1>Eksperyment</h1>
        <p className="subtitle">Tydzień z AI · konfiguracja</p>
        <Setup status={st} onSaved={() => { setView('main'); void reload(); }} onCancel={st ? () => setView('main') : undefined} />
      </AppShell>
    );
  }

  const e = st.experiment;
  if (view === 'report' || (finished && view === 'main')) {
    return (
      <AppShell>
        <h1>Eksperyment</h1>
        <p className="subtitle">{finished ? 'Zakończony · raport tygodnia' : `Raport do dnia ${st.dayIndex}`}</p>
        <ReportView id={e.id} />
        <div className="row" style={{ marginTop: 16 }}>
          {!finished && <button className="btn" onClick={() => setView('main')}>Wróć</button>}
          {finished && <button className="btn btn-primary" onClick={() => setView('setup')}>Nowy eksperyment</button>}
        </div>
      </AppShell>
    );
  }

  const statusLabel = e.status === 'draft' ? 'Szkic' : e.status === 'paused' ? 'Wstrzymany' : 'Aktywny';
  const b = st.budget;
  return (
    <AppShell>
      <h1>Eksperyment</h1>
      <p className="subtitle">Tydzień z AI · {statusLabel} · <Link href="/ujecia/" style={{ color: 'var(--cyan)' }}>Oś ujęć</Link></p>

      {e.status === 'draft' ? (
        <div className="card" style={{ marginTop: 12 }}>
          <div className="label">Gotowe do startu</div>
          <div style={{ fontSize: 18, fontWeight: 600 }}>{e.goal}</div>
          <p className="hint">Start {e.startDate} · {e.days} dni · budżet {pln(e.budgetGrosze)} · reguły v{e.rulesVersion}</p>
          <div className="row">
            <button className="btn btn-primary" onClick={() => act(() => Api.transition(e.id, 'activate'))}>Rozpocznij eksperyment</button>
            <button className="btn" onClick={() => setView('rules')}>Edytuj reguły</button>
          </div>
        </div>
      ) : (
        <>
          <div style={{ fontSize: 26, fontWeight: 700, margin: '14px 0 8px' }}>
            {st.dayIndex < 1 ? `Start ${e.startDate}` : `Dzień ${Math.min(st.dayIndex, e.days)} z ${e.days}`}
          </div>

          <div className="card">
            <div className="label">Budżet na aktywności</div>
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 10 }}>
              <span style={{ fontSize: 38, fontWeight: 800, color: b.overLimit ? 'var(--warn)' : 'var(--cyan)' }}>{pln(b.availableGrosze)}</span>
              <span className="hint">dostępne</span>
            </div>
            <div className="hint">Wydano {pln(b.spentGrosze)} · Rezerwy {pln(b.reservedGrosze)}</div>
            <div className="hint">Limit tygodnia {pln(b.limitGrosze)}{b.overLimit ? ' · przekroczony, płatne propozycje zablokowane' : ''}</div>
          </div>

          <div className="row" style={{ marginTop: 10 }}>
            <div className="card"><div style={{ fontSize: 22, fontWeight: 700 }}>{st.refusals}</div><div className="hint">{st.refusals === 1 ? 'odmowa' : 'odmowy'}</div></div>
            <div className="card"><div style={{ fontSize: 22, fontWeight: 700 }}>{st.tasksDone} z {st.tasksTotal}</div><div className="hint">zadań dziś</div></div>
          </div>

          <ReviewForm st={st} onSave={(r, c) => act(() => Api.review(st.experimentDate, r, c))} />
          <ExpenseForm onSave={(amount, desc) => act(() => Api.expense(amount, desc, newKey()))} />

          <div className="card" style={{ marginTop: 10 }}>
            <div className="label">Koszt API (osobno od budżetu)</div>
            <div>{st.api.minutes} min rozmów · ok. {pln(st.api.estimatedGrosze)} z limitu {pln(st.api.limitGrosze)}{st.api.overLimit ? ' · limit wyczerpany' : ''}</div>
          </div>

          {msg && <p className="error">{msg}</p>}
          <div className="row" style={{ marginTop: 14 }}>
            <button className="btn" onClick={() => setView('rules')}>Reguły</button>
            <button className="btn" onClick={() => setView('report')}>Raport</button>
          </div>
          <div className="row" style={{ marginTop: 10 }}>
            {e.status === 'active'
              ? <button className="btn" onClick={() => act(() => Api.transition(e.id, 'pause', window.prompt('Powód pauzy (opcjonalnie)') ?? undefined))}>Pauza</button>
              : <button className="btn btn-primary" onClick={() => act(() => Api.transition(e.id, 'resume'))}>Wznów</button>}
            <button className="btn btn-danger" onClick={() => {
              if (window.confirm('Zakończyć eksperyment przed czasem? Decyzja zostanie zapisana.')) void act(() => Api.transition(e.id, 'end', 'Zakończony wcześniej'));
            }}>Zakończ wcześniej</button>
          </div>
          {view === 'rules' && <Rules st={st} onClose={() => setView('main')} />}
        </>
      )}
      {msg && e.status === 'draft' && <p className="error">{msg}</p>}
    </AppShell>
  );
}

function Rules({ st, onClose }: { st: ExperimentStatus; onClose: () => void }) {
  const e = st.experiment;
  return (
    <div className="card" style={{ marginTop: 12 }}>
      <div className="label">Reguły v{e.rulesVersion}</div>
      <ul style={{ margin: 0, paddingLeft: 18, lineHeight: 1.6 }}>
        <li>Cel: {e.goal}</li>
        <li>{e.startDate} – {e.days} dni, doba od {e.dayStart}</li>
        <li>Praca {e.workStart}–{e.workEnd}</li>
        <li>Budżet {pln(e.budgetGrosze)}, limit API {pln(e.apiLimitGrosze)}</li>
        <li>Wykluczone: {e.exclusions.length ? e.exclusions.join(', ') : 'nic'}</li>
        {e.baseline.usualWeekRating && <li>Zwykły tydzień: ocena {e.baseline.usualWeekRating}/5, aktywności {e.baseline.usualActivities ?? '–'}</li>}
      </ul>
      <button className="btn btn-sm" style={{ marginTop: 10 }} onClick={onClose}>Zamknij</button>
    </div>
  );
}

function ReviewForm({ st, onSave }: { st: ExperimentStatus; onSave: (rating: number, comment: string) => void }) {
  const [rating, setRating] = useState(st.todayReview?.rating ?? 0);
  const [comment, setComment] = useState(st.todayReview?.comment ?? '');
  return (
    <div className="card" style={{ marginTop: 10 }}>
      <div className="label">{st.todayReview ? `Ocena dnia · ${st.todayReview.rating}/5 (możesz zmienić)` : 'Oceń dzień'}</div>
      <div className="row" role="radiogroup" aria-label="Ocena 1–5">
        {[1, 2, 3, 4, 5].map((n) => (
          <button key={n} className={`btn btn-sm ${rating === n ? 'btn-on' : ''}`} role="radio" aria-checked={rating === n} onClick={() => setRating(n)}>{n}</button>
        ))}
      </div>
      <input style={{ marginTop: 8 }} value={comment} onChange={(e) => setComment(e.target.value)} placeholder="Krótki komentarz" maxLength={1000} />
      <button className="btn btn-primary btn-sm" style={{ marginTop: 8, width: '100%' }} disabled={!rating} onClick={() => onSave(rating, comment)}>Zapisz ocenę</button>
    </div>
  );
}

function ExpenseForm({ onSave }: { onSave: (amountPln: number, description: string) => void }) {
  const [amount, setAmount] = useState('');
  const [desc, setDesc] = useState('');
  const value = Number(amount.replace(',', '.'));
  return (
    <div className="card" style={{ marginTop: 10 }}>
      <div className="label">Dodaj wydatek</div>
      <div className="row">
        <input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="zł" aria-label="Kwota w zł" />
        <input value={desc} onChange={(e) => setDesc(e.target.value)} placeholder="Na co" aria-label="Opis wydatku" />
      </div>
      <button className="btn btn-sm" style={{ marginTop: 8, width: '100%' }} disabled={!(value > 0) || !desc.trim()}
              onClick={() => { onSave(value, desc.trim()); setAmount(''); setDesc(''); }}>Zapisz wydatek</button>
    </div>
  );
}

function Setup({ status, onSaved, onCancel }: { status?: ExperimentStatus; onSaved: () => void; onCancel?: () => void }) {
  const draft = status?.experiment.status === 'draft' ? status.experiment : null;
  const today = new Date().toLocaleDateString('sv-SE');
  const [c, setC] = useState<ExperimentConfig>(() => ({
    goal: draft?.goal ?? 'Czy AI zaplanuje mi lepszy tydzień niż ja sam?',
    startDate: draft?.startDate ?? today,
    workStart: draft?.workStart ?? '09:00',
    workEnd: draft?.workEnd ?? '17:00',
    exclusions: draft?.exclusions ?? [],
    budgetPln: draft ? draft.budgetGrosze / 100 : 1000,
    apiLimitPln: draft ? draft.apiLimitGrosze / 100 : 100,
    dayStart: draft?.dayStart ?? '04:00',
    baselineRating: draft?.baseline.usualWeekRating ?? 3,
    baselineActivities: draft?.baseline.usualActivities ?? 2,
  }));
  const [excl, setExcl] = useState((draft?.exclusions ?? []).join(', '));
  const [msg, setMsg] = useState<string | null>(null);

  async function save() {
    setMsg(null);
    const cfg = { ...c, exclusions: excl.split(',').map((s) => s.trim()).filter(Boolean) };
    try {
      if (draft) await Api.updateExperiment(draft.id, cfg);
      else await Api.createExperiment(cfg);
      onSaved();
    } catch (e) {
      setMsg((e as Error).message);
    }
  }

  const set = <K extends keyof ExperimentConfig>(k: K, v: ExperimentConfig[K]) => setC({ ...c, [k]: v });
  return (
    <div style={{ marginTop: 12 }}>
      <label className="field"><span>Cel</span><input value={c.goal} onChange={(e) => set('goal', e.target.value)} /></label>
      <div className="row">
        <label className="field"><span>Start</span><input type="date" value={c.startDate} onChange={(e) => set('startDate', e.target.value)} /></label>
        <label className="field"><span>Początek doby</span><input type="time" value={c.dayStart} onChange={(e) => set('dayStart', e.target.value)} /></label>
      </div>
      <div className="row">
        <label className="field"><span>Praca od</span><input type="time" value={c.workStart} onChange={(e) => set('workStart', e.target.value)} /></label>
        <label className="field"><span>Praca do</span><input type="time" value={c.workEnd} onChange={(e) => set('workEnd', e.target.value)} /></label>
      </div>
      <div className="row">
        <label className="field"><span>Budżet na aktywności (zł)</span><input type="number" min={0} value={c.budgetPln} onChange={(e) => set('budgetPln', Number(e.target.value))} /></label>
        <label className="field"><span>Limit kosztów API (zł)</span><input type="number" min={0} value={c.apiLimitPln} onChange={(e) => set('apiLimitPln', Number(e.target.value))} /></label>
      </div>
      <label className="field"><span>Wykluczone aktywności (po przecinku)</span><input value={excl} onChange={(e) => setExcl(e.target.value)} placeholder="np. alkohol, bieganie" /></label>
      <div className="section-title">Punkt odniesienia: zwykły tydzień</div>
      <div className="row">
        <label className="field"><span>Ocena 1–5</span><input type="number" min={1} max={5} value={c.baselineRating ?? ''} onChange={(e) => set('baselineRating', Number(e.target.value))} /></label>
        <label className="field"><span>Aktywności</span><input type="number" min={0} value={c.baselineActivities ?? ''} onChange={(e) => set('baselineActivities', Number(e.target.value))} /></label>
      </div>
      {msg && <p className="error">{msg}</p>}
      <div className="row">
        <button className="btn btn-primary" onClick={save}>{draft ? 'Zapisz reguły' : 'Utwórz szkic'}</button>
        {onCancel && <button className="btn" onClick={onCancel}>Anuluj</button>}
      </div>
      <p className="hint">Reguły można zmieniać do startu. Po starcie są zamrożone (wersja zapisana).</p>
    </div>
  );
}

function ReportView({ id }: { id: string }) {
  const { data: r, error } = useLive<Report>(() => Api.report(id), ['review.saved', 'budget.changed', 'task.status'], [id]);
  if (!r) return <p className="hint">{error ?? 'Wczytywanie…'}</p>;
  const base = r.baseline.usualWeekRating;
  return (
    <>
      <div className="row" style={{ marginTop: 12 }}>
        <div className="card"><div style={{ fontSize: 24, fontWeight: 700 }}>{r.averageRating ?? '–'}</div><div className="hint">średnia ocena{base ? ` (zwykle ${base})` : ''}</div></div>
        <div className="card"><div style={{ fontSize: 24, fontWeight: 700 }}>{r.done}/{r.planned}</div><div className="hint">wykonane / zaplanowane</div></div>
      </div>
      <div className="row" style={{ marginTop: 10 }}>
        <div className="card"><div style={{ fontSize: 24, fontWeight: 700 }}>{r.refused}</div><div className="hint">odmowy</div></div>
        <div className="card"><div style={{ fontSize: 24, fontWeight: 700 }}>{pln(r.budget.spentGrosze)}</div><div className="hint">wydano z {pln(r.budget.limitGrosze)}</div></div>
      </div>
      <div className="card" style={{ marginTop: 10, overflowX: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 14 }}>
          <thead><tr className="hint" style={{ textAlign: 'left' }}><th>Dzień</th><th>Ocena</th><th>Zrobione</th><th>Odmowy</th><th>Wydano</th></tr></thead>
          <tbody>
            {r.days.map((d) => (
              <tr key={d.day} style={{ borderTop: '1px solid var(--line)' }}>
                <td style={{ padding: '6px 0' }}>{d.day} · {d.date.slice(5)}</td>
                <td>{d.rating ?? '–'}</td>
                <td>{d.done}/{d.planned}</td>
                <td>{d.refused}</td>
                <td>{d.spentGrosze ? pln(d.spentGrosze) : '–'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {r.refusals.length > 0 && (
        <div className="card" style={{ marginTop: 10 }}>
          <div className="label">Odmowy</div>
          {r.refusals.map((x, i) => <div key={i}>{x.date.slice(5)} · {x.title}{x.reason ? ` · ${x.reason}` : ''}</div>)}
        </div>
      )}
      {r.expenses.length > 0 && (
        <div className="card" style={{ marginTop: 10 }}>
          <div className="label">Wydatki</div>
          {r.expenses.map((x) => <div key={x.id}>{x.date.slice(5)} · {x.description} · {pln(x.amountGrosze)}</div>)}
        </div>
      )}
      <p className="hint">API: {r.api.minutes} min, ok. {pln(r.api.estimatedGrosze)} (poza budżetem aktywności).</p>
    </>
  );
}
