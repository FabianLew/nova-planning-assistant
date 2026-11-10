'use client';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useEffect, useState } from 'react';
import { Api } from '@/lib/api';

const TABS = [
  { href: '/rozmowa/', label: 'Rozmowa' },
  { href: '/dzien/', label: 'Mój dzień' },
  { href: '/pamiec/', label: 'Pamięć' },
  { href: '/eksperyment/', label: 'Eksperyment' },
];

/** Auth guard + top bar + bottom tab bar shared by the four main screens. */
export function AppShell({ children }: { children: React.ReactNode }) {
  const path = usePathname();
  const [ok, setOk] = useState(false);

  useEffect(() => {
    Api.me().then(() => setOk(true)).catch(() => (window.location.href = '/login/'));
  }, []);

  if (!ok) return <main className="app" />;

  return (
    <>
      <main className="app">
        <div className="topbar">
          <span>Future IRL</span>
          <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            NOVA / MVP
            <Link href="/ustawienia/" aria-label="Ustawienia">⚙︎</Link>
          </span>
        </div>
        {children}
      </main>
      <div className="tabbar">
        <nav>
          {TABS.map((t) => (
            <Link key={t.href} href={t.href} aria-current={path?.startsWith(t.href.slice(0, -1)) ? 'page' : undefined}>
              {t.label}
            </Link>
          ))}
        </nav>
      </div>
    </>
  );
}

export function ComingSoon({ title, step }: { title: string; step: string }) {
  return (
    <AppShell>
      <h1>{title}</h1>
      <p className="subtitle">Ten ekran powstaje w kroku {step} planu.</p>
    </AppShell>
  );
}
