import type { VoiceState } from '@/lib/turns';

export const STATE_LABEL: Record<VoiceState, string> = {
  idle: 'Gotowa',
  connecting: 'Łączenie',
  listening: 'Słucham',
  speaking: 'Mówię',
  disconnected: 'Rozłączono',
  ended: 'Zakończono',
  error: 'Błąd',
};

/** Large status indicator. Colour is never the only signal: the state is always written below the orb. */
export function Orb({ state, userSpeaking, muted }: { state: VoiceState; userSpeaking: boolean; muted: boolean }) {
  const label = muted && (state === 'listening' || state === 'speaking') ? 'Wyciszony' : STATE_LABEL[state];
  return (
    <div className="orb-wrap" role="status" aria-live="polite">
      <div className={`orb orb-${state} ${userSpeaking ? 'orb-user' : ''} ${muted ? 'orb-muted' : ''}`} aria-hidden="true">
        <div className="orb-core" />
      </div>
      <div className="orb-label">{label}</div>
      <style>{`
        .orb-wrap { display: flex; flex-direction: column; align-items: center; gap: 14px; margin: 22px 0 18px; }
        .orb { width: 176px; height: 176px; border-radius: 50%; display: grid; place-items: center;
               background: radial-gradient(circle, rgba(47,211,224,0.28) 0%, rgba(47,211,224,0.06) 55%, transparent 70%);
               transition: transform 200ms ease; }
        .orb-core { width: 112px; height: 112px; border-radius: 50%;
                    background: radial-gradient(circle at 35% 30%, #b8f6fb 0%, #2fd3e0 42%, #0f8d99 100%);
                    box-shadow: 0 0 48px rgba(47,211,224,0.55); }
        .orb-idle .orb-core, .orb-ended .orb-core { filter: saturate(0.3) brightness(0.7); box-shadow: none; }
        .orb-connecting .orb-core { animation: orb-breathe 1.2s ease-in-out infinite; opacity: 0.8; }
        .orb-listening .orb-core { animation: orb-breathe 3s ease-in-out infinite; }
        .orb-user { transform: scale(1.06); }
        .orb-speaking .orb-core { animation: orb-talk 0.45s ease-in-out infinite alternate; }
        .orb-disconnected .orb-core, .orb-error .orb-core { background: radial-gradient(circle at 35% 30%, #ffe0a8, #ffb547 50%, #a86a10); box-shadow: 0 0 30px rgba(255,181,71,0.4); }
        .orb-muted .orb-core { filter: grayscale(0.8); }
        .orb-label { font-size: 28px; font-weight: 700; letter-spacing: -0.01em; }
        @keyframes orb-breathe { 0%,100% { transform: scale(0.96); } 50% { transform: scale(1.03); } }
        @keyframes orb-talk { from { transform: scale(0.97); } to { transform: scale(1.09); } }
        @media (prefers-reduced-motion: reduce) { .orb-core { animation: none !important; } }
      `}</style>
    </div>
  );
}
