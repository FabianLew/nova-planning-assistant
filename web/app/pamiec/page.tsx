'use client';
import { useState } from 'react';
import { AppShell } from '@/components/AppShell';
import { Api, type Memory } from '@/lib/api';
import { useLive } from '@/lib/hooks';

type Filter = 'fact' | 'inference' | 'episode';
const FILTERS: { id: Filter; label: string }[] = [
  { id: 'fact', label: 'Fakty' },
  { id: 'inference', label: 'Wnioski' },
  { id: 'episode', label: 'Epizody' },
];
const KIND_LABEL: Record<Memory['kind'], string> = { fact: 'Fakt', inference: 'Wniosek', episode: 'Epizod' };

/** Memory screen (spec p.4, mockup p.12): source, type and date; correct, delete, confirm or reject. */
export default function MemoryPage() {
  const { data, error, reload } = useLive(() => Api.memories(), ['memory.changed', 'memory.deleted']);
  const [filter, setFilter] = useState<Filter>('fact');
  const [editing, setEditing] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const [newFact, setNewFact] = useState('');
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

  const all = data ?? [];
  const pendingCount = all.filter((m) => m.status === 'pending').length;
  const shown = all.filter((m) => m.kind === filter);

  return (
    <AppShell>
      <h1>Pamięć</h1>
      <p className="subtitle">Fakty, wnioski i wydarzenia{pendingCount ? ` · ${pendingCount} do oceny` : ''}</p>
      <div className="tabs">
        {FILTERS.map((f) => (
          <button key={f.id} aria-pressed={filter === f.id} onClick={() => setFilter(f.id)}>
            {f.label} ({all.filter((m) => m.kind === f.id).length})
          </button>
        ))}
      </div>
      {!data && <p className="hint">{error ?? 'Wczytywanie…'}</p>}
      <div className="list">
        {data && shown.length === 0 && <p className="hint">Pusto.</p>}
        {shown.map((m) => (
          <div key={m.id} className="card">
            <div className="label">{KIND_LABEL[m.kind]} / {m.status === 'pending' ? 'do oceny' : 'potwierdzony'}</div>
            {editing === m.id ? (
              <>
                <input value={draft} onChange={(e) => setDraft(e.target.value)} aria-label="Treść wpisu" />
                <div className="row" style={{ marginTop: 8 }}>
                  <button className="btn btn-sm btn-primary" disabled={!draft.trim()}
                          onClick={() => act(async () => { await Api.editMemory(m.id, draft.trim(), m.version); setEditing(null); })}>Zapisz</button>
                  <button className="btn btn-sm" onClick={() => setEditing(null)}>Anuluj</button>
                </div>
              </>
            ) : (
              <div style={{ fontSize: 18 }}>{m.status === 'pending' ? `${m.content}?` : `„${m.content}”`}</div>
            )}
            <div className="hint" style={{ marginTop: 4 }}>
              Źródło · {m.sourceNote ?? 'rozmowa'} · {new Date(m.createdAt).toLocaleDateString('pl-PL')}{m.version > 1 ? ` · wersja ${m.version}` : ''}
            </div>
            {editing !== m.id && (
              <div className="row" style={{ marginTop: 10 }}>
                {m.status === 'pending' ? (
                  <>
                    <button className="btn btn-sm btn-primary" onClick={() => act(() => Api.confirmMemory(m.id))}>Potwierdź</button>
                    <button className="btn btn-sm" onClick={() => act(() => Api.rejectMemory(m.id))}>Odrzuć</button>
                  </>
                ) : (
                  <>
                    <button className="btn btn-sm" onClick={() => { setEditing(m.id); setDraft(m.content); }}>Popraw wpis</button>
                    <button className="btn btn-sm btn-danger" onClick={() => {
                      if (window.confirm('Usunąć ten wpis? NOVA przestanie go pamiętać.')) void act(() => Api.deleteMemory(m.id));
                    }}>Usuń</button>
                  </>
                )}
              </div>
            )}
          </div>
        ))}
      </div>
      {msg && <p className="error">{msg}</p>}
      {filter === 'fact' && (
        <div className="card" style={{ marginTop: 16 }}>
          <label className="field"><span>Dodaj fakt o sobie</span>
            <input value={newFact} onChange={(e) => setNewFact(e.target.value)} placeholder="np. Pracuję do 17" maxLength={500} />
          </label>
          <button className="btn btn-primary" style={{ width: '100%' }} disabled={!newFact.trim()}
                  onClick={() => act(async () => { await Api.addMemory(newFact.trim()); setNewFact(''); })}>Zapamiętaj</button>
        </div>
      )}
    </AppShell>
  );
}
