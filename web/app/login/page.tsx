'use client';
import { useState } from 'react';
import { Api, ApiError } from '@/lib/api';

export default function LoginPage() {
  const [username, setUsername] = useState('fabian');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await Api.login(username, password);
      window.location.href = '/rozmowa/';
    } catch (err) {
      setError(err instanceof ApiError && err.status === 401 ? 'Zły login lub hasło.' : err instanceof Error ? err.message : 'Błąd logowania');
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="app" style={{ justifyContent: 'center' }}>
      <div className="topbar"><span>Future IRL</span><span>NOVA / MVP</span></div>
      <h1>Zaloguj się</h1>
      <form onSubmit={submit} style={{ marginTop: 16 }}>
        <label className="field"><span>Login</span>
          <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" autoCapitalize="none" />
        </label>
        <label className="field"><span>Hasło</span>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
        </label>
        {error && <p className="error">{error}</p>}
        <button className="btn btn-primary" style={{ width: '100%' }} disabled={busy || !password}>Zaloguj</button>
      </form>
    </main>
  );
}
