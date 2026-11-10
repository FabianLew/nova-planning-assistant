'use client';
import { useCallback, useEffect, useState } from 'react';
import { subscribe } from './events';

/** Loads data and reloads it when one of the given server event types arrives over SSE. */
export function useLive<T>(load: () => Promise<T>, eventTypes: string[], deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    try {
      setData(await load());
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Błąd');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  useEffect(() => {
    void reload();
    return subscribe((e) => {
      if (eventTypes.includes(e.type)) void reload();
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reload]);

  return { data, error, reload };
}

export function newKey(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

export function hhmm(iso: string | null, timeZone?: string): string {
  if (!iso) return '–';
  return new Date(iso).toLocaleTimeString('pl-PL', { hour: '2-digit', minute: '2-digit', timeZone });
}

export function pln(grosze: number): string {
  return (grosze / 100).toLocaleString('pl-PL', { style: 'currency', currency: 'PLN' });
}
