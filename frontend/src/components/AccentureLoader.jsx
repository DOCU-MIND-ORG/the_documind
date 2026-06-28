import React from 'react';

/**
 * AccentureLoader
 * ----------------
 * A small "thinking" indicator built around Accenture's actual signature
 * mark: a single purple ">" accent, not a row of arrows. Used anywhere we
 * previously showed the 3-dot Constellation loader while waiting for the
 * assistant's first token.
 *
 * Pure CSS/SVG animation (no canvas, no rAF loop) — cheap to mount many times
 * and looks identical in light/dark mode since it only uses its own colors.
 *
 * Visual: just a soft glowing halo breathing behind a single chevron that
 * pulses in place — no orbiting/spinning elements, kept deliberately simple.
 */
export default function AccentureLoader({ className = '', style = {} }) {
  return (
    <span
      className={`accenture-loader ${className}`}
      style={style}
      role="status"
      aria-label="Thinking…"
    >
      <svg viewBox="0 0 64 64" className="accenture-loader-svg" aria-hidden="true">
        {/* soft halo breathing behind the mark */}
        <circle className="al-halo" cx="32" cy="32" r="22" />

        {/* the single chevron stroke — Accenture's actual accent mark */}
        <path className="al-chevron" d="M22 16 L42 32 L22 48" />
      </svg>
    </span>
  );
}
