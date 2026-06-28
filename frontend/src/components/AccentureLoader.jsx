import React from 'react';

/**
 * AccentureLoader
 * ----------------
 * A small, playful "thinking" indicator themed around Accenture's signature
 * purple angle-bracket accent mark. Used anywhere we previously showed the
 * 3-dot Constellation loader while waiting for the assistant's first token.
 *
 * Pure CSS/SVG animation (no canvas, no rAF loop) — cheap to mount many times
 * and looks identical in light/dark mode since it only uses its own colors.
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

        {/* the three chevron strokes that make up the Accenture accent mark,
            drawn one after another like a little hop */}
        <g className="al-chevrons">
          <path className="al-chevron al-chevron-1" d="M14 20 L26 32 L14 44" />
          <path className="al-chevron al-chevron-2" d="M26 20 L38 32 L26 44" />
          <path className="al-chevron al-chevron-3" d="M38 20 L50 32 L38 44" />
        </g>

        {/* tiny sparkle that orbits the mark, just for a bit of charm */}
        <circle className="al-sparkle" r="2.2" cx="32" cy="10" />
      </svg>
    </span>
  );
}
