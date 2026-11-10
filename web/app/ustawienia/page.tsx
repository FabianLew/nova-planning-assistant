'use client';
import { useEffect, useState } from 'react';
import { AppShell } from '@/components/AppShell';
import { Api, ApiError, type Profile } from '@/lib/api';

const VOICES = ['marin', 'cedar', 'alloy', 'ash', 'ballad', 'coral', 'echo', 'sage', 'shimmer', 'verse'];

/** Name, voice and personality are configurable (spec p.1); VAD settings are tuned during M1. */
export default function SettingsPage() {
  const [p, setP] = useState<Profile | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => { Api.profile().then(setP).catch((e) => setMsg(e.message)); }, []);

  async function save() {
    if (!p) return;
    setBusy(true);
    setMsg(null);
    try {
      setP(await Api.saveProfile(p));
      setMsg('Zapisano. Zmiany działają od następnej rozmowy.');
    } catch (e) {
      setMsg(e instanceof ApiError && e.code === 'stale_version' ? 'Ktoś zmienił ustawienia w międzyczasie. Odśwież stronę.' : (e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function logout() {
    await Api.logout().catch(() => {});
    window.location.href = '/login/';
  }

  return (
    <AppShell>
      <h1>Ustawienia</h1>
      <p className="subtitle">Nazwa, głos i osobowość asystenta</p>
      {!p ? <p className="hint">{msg ?? 'Wczytywanie…'}</p> : (
        <div style={{ marginTop: 12 }}>
          <label className="field"><span>Nazwa ekranowa</span>
            <input value={p.displayName} maxLength={40} onChange={(e) => setP({ ...p, displayName: e.target.value })} />
          </label>
          <label className="field"><span>Głos</span>
            <select value={p.voice} onChange={(e) => setP({ ...p, voice: e.target.value })}>
              {(VOICES.includes(p.voice) ? VOICES : [p.voice, ...VOICES]).map((v) => <option key={v} value={v}>{v}</option>)}
            </select>
          </label>
          <label className="field"><span>Instrukcja osobowości</span>
            <textarea value={p.instructions} onChange={(e) => setP({ ...p, instructions: e.target.value })} />
          </label>
          <label className="field"><span>Wykrywanie końca wypowiedzi</span>
            <select value={p.turnDetection} onChange={(e) => setP({ ...p, turnDetection: e.target.value as Profile['turnDetection'] })}>
              <option value="server_vad">Cisza (server_vad), przewidywalne pomiary</option>
              <option value="semantic_vad">Semantyczne (semantic_vad), cierpliwsze przy namyśle</option>
            </select>
          </label>
          {p.turnDetection === 'server_vad' ? (
            <label className="field"><span>Tolerancja ciszy: {p.vadSilenceMs} ms</span>
              <input type="range" min={200} max={1500} step={50} value={p.vadSilenceMs}
                     onChange={(e) => setP({ ...p, vadSilenceMs: Number(e.target.value) })} />
            </label>
          ) : (
            <label className="field"><span>Cierpliwość (eagerness)</span>
              <select value={p.vadEagerness} onChange={(e) => setP({ ...p, vadEagerness: e.target.value as Profile['vadEagerness'] })}>
                <option value="low">low (najdłużej czeka)</option>
                <option value="medium">medium</option>
                <option value="auto">auto</option>
                <option value="high">high (odpowiada najszybciej)</option>
              </select>
            </label>
          )}
          {msg && <p className="hint">{msg}</p>}
          <div className="row">
            <button className="btn btn-primary" onClick={save} disabled={busy}>Zapisz</button>
            <button className="btn" onClick={logout}>Wyloguj</button>
          </div>
        </div>
      )}
    </AppShell>
  );
}
