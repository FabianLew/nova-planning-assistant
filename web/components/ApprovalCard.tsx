'use client';
import { useEffect, useRef, useState } from 'react';
import { Api, ApiError, type Approval, type Task } from '@/lib/api';
import { hhmm, newKey, pln } from '@/lib/hooks';

const OUTCOME: Record<string, string> = {
  approved: 'Zatwierdzone',
  rejected: 'Odrzucone',
  expired: 'Karta wygasła, poproś o nową',
  superseded: 'Plan zmienił się w międzyczasie, ta karta jest nieaktualna',
};

/**
 * Approval card (spec p.5, mockup p.11): shows the action, cost and conflict; reject sits next to approve.
 * The plan changes only after this tap and the server's confirmation. One key per card makes a double tap safe.
 */
export function ApprovalCard({ approval, tasks = [], timeZone, onDecided }: {
  approval: Approval;
  tasks?: Task[];
  timeZone?: string;
  onDecided?: (outcome: string, approval: Approval) => void;
}) {
  const key = useRef(newKey());
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<string | null>(null);
  const [left, setLeft] = useState(() => msLeft(approval.expiresAt));

  useEffect(() => {
    const t = setInterval(() => setLeft(msLeft(approval.expiresAt)), 1000);
    return () => clearInterval(t);
  }, [approval.expiresAt]);

  async function decide(decision: 'approve' | 'reject') {
    setBusy(true);
    try {
      const r = await Api.decide(approval.id, decision, key.current);
      setResult(OUTCOME[r.outcome] ?? r.outcome);
      onDecided?.(r.outcome, approval);
    } catch (e) {
      setResult(e instanceof ApiError && e.code === 'already_decided' ? 'Ta karta została już rozstrzygnięta.' : (e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const proposed = tasks.filter((t) => t.proposalId === approval.id);
  const isChange = approval.kind === 'plan_change';
  const total = proposed.reduce((s, t) => s + t.costGrosze, 0);

  return (
    <div className="card approval" role="group" aria-label="Karta zgody">
      <div className="label">{approval.conflict && isChange ? 'Konflikt w planie' : isChange ? 'Zmiana planu' : 'Propozycja planu'}</div>
      <div style={{ fontSize: 19, fontWeight: 700, marginBottom: 4 }}>
        {isChange ? `Przenieść „${approval.args.title}”?` : approval.summary}
      </div>
      {isChange && <div style={{ fontSize: 17 }}>{approval.args.from ?? 'bez godziny'} → {approval.args.to}</div>}
      {proposed.length > 0 && (
        <ul style={{ margin: '8px 0', paddingLeft: 18 }}>
          {proposed.map((t) => (
            <li key={t.id}>
              <b>{hhmm(t.startAt, timeZone)}</b> {t.title} · {t.durationMin} min{t.costGrosze > 0 ? ` · ${pln(t.costGrosze)}` : ''}
              {t.reason && <div className="hint">{t.reason}</div>}
            </li>
          ))}
        </ul>
      )}
      {total > 0 && <div>Koszt: <b>{pln(total)}</b></div>}
      {approval.conflict && <div className="hint" style={{ color: 'var(--warn)', marginTop: 4 }}>⚠︎ {approval.conflict}</div>}
      {result ? (
        <p style={{ margin: '10px 0 0', fontWeight: 600 }}>{result}</p>
      ) : (
        <>
          <div className="row" style={{ marginTop: 12 }}>
            <button className="btn btn-primary" disabled={busy || left <= 0} onClick={() => decide('approve')}>
              {isChange ? `Przenieś na ${approval.args.to}` : 'Zatwierdź plan'}
            </button>
            <button className="btn" disabled={busy} onClick={() => decide('reject')}>
              {isChange ? 'Odrzuć' : 'Odeślij do poprawy'}
            </button>
          </div>
          <div className="hint" style={{ marginTop: 6 }}>
            {left > 0 ? `Ważna jeszcze ${Math.ceil(left / 60000)} min` : 'Wygasła'}
          </div>
        </>
      )}
    </div>
  );
}

function msLeft(iso: string) {
  return new Date(iso).getTime() - Date.now();
}

/** Text the voice session passes to the model after a decision. */
export function decisionNote(outcome: string, a: Approval): string {
  const what = a.kind === 'plan_change' ? `przeniesienie „${a.args.title}” na ${a.args.to}` : `plan (${a.summary})`;
  switch (outcome) {
    case 'approved': return `Twórca zatwierdził na ekranie: ${what}. Zmiana jest zapisana.`;
    case 'rejected': return `Twórca odrzucił na ekranie: ${what}. Nic się nie zmieniło.`;
    case 'expired': return `Karta wygasła: ${what}. Nic się nie zmieniło.`;
    default: return `Karta jest nieaktualna, bo plan zmienił się w międzyczasie: ${what}. Nic się nie zmieniło.`;
  }
}
