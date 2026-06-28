import React, { useEffect, useRef } from 'react';

function ConstellationDark({ className = '', style = {} }) {
  const pathRef = useRef(null);
  const blueRef = useRef(null);
  const redRef = useRef(null);
  const orangeRef = useRef(null);

  useEffect(() => {
    const loopPath = pathRef.current;
    if (!loopPath) return;

    // Must wait for DOM to be ready for getTotalLength
    const totalLength = loopPath.getTotalLength();
    if (!totalLength) return;

    const dots = [
      { ref: blueRef, offset: 0.0, speedMult: 1.0 },
      { ref: redRef, offset: 0.34, speedMult: 0.85 },
      { ref: orangeRef, offset: 0.67, speedMult: 1.15 },
    ];

    let baseSpeed = 7;
    let lastTime = null;
    let elapsed = 0;
    let animationFrameId;

    const frame = (timestamp) => {
      if (lastTime === null) lastTime = timestamp;
      const dt = (timestamp - lastTime) / 1000;
      lastTime = timestamp;
      elapsed += dt;

      dots.forEach(dot => {
        const cycleTime = baseSpeed / dot.speedMult;
        let progress = ((elapsed / cycleTime) + dot.offset) % 1;
        const point = loopPath.getPointAtLength(progress * totalLength);
        if (dot.ref.current) {
          dot.ref.current.setAttribute('transform', `translate(${point.x}, ${point.y})`);
        }
      });
      animationFrameId = requestAnimationFrame(frame);
    };

    animationFrameId = requestAnimationFrame(frame);
    return () => cancelAnimationFrame(animationFrameId);
  }, []);

  return (
    <svg viewBox="0 0 226 226" className={`dark:hidden inline-block ${className}`} style={style} aria-hidden="true">
      <defs>
        <filter id="dotBlur" x="-300%" y="-300%" width="700%" height="700%">
          <feGaussianBlur stdDeviation="5" result="blur"/>
          <feMerge><feMergeNode in="blur"/><feMergeNode in="blur"/></feMerge>
        </filter>
      </defs>

      <path className="constellation-line" d="M 168 47 A 80 80 0 1 0 63 187"/>
      <path className="constellation-line" d="M 73 193 A 80 80 0 0 0 165 52"/>

      <path className="constellation-line" d="M 60 110 C 87 100, 100 117, 123 107 C 143 98, 153 86, 168 47"/>
      <path className="constellation-line" d="M 53 143 C 80 133, 107 150, 133 137 C 153 127, 163 110, 168 47"/>
      <path className="constellation-line" d="M 72 188 C 93 173, 110 180, 127 167 C 147 152, 157 130, 165 52"/>
      <path className="constellation-line" d="M 87 203 C 107 190, 123 197, 143 178 C 163 161, 165 133, 165 52"/>
      <path className="constellation-line" d="M 63 187 C 100 173, 116 193, 147 170 C 165 154, 165 133, 165 52"/>

      <path ref={pathRef}
            d="M 168 47 A 80 80 0 1 0 63 187 C 100 173, 116 193, 147 170 C 165 154, 167 120, 168 47"
            fill="none" stroke="none"/>

      <circle className="constellation-node-dot" cx="63" cy="187" r="11"/>
      <circle className="constellation-node-dot" cx="168" cy="47" r="9.5"/>

      <g ref={blueRef}>
        <circle className="constellation-glow-dot-halo" r="9" fill="#5FA8FF"/>
        <circle className="constellation-glow-dot-core" r="3.4" fill="#EAF3FF"/>
      </g>
      <g ref={redRef}>
        <circle className="constellation-glow-dot-halo" r="9" fill="#FF5C5C"/>
        <circle className="constellation-glow-dot-core" r="3.4" fill="#FFE3E3"/>
      </g>
      <g ref={orangeRef}>
        <circle className="constellation-glow-dot-halo" r="9" fill="#FFA64D"/>
        <circle className="constellation-glow-dot-core" r="3.4" fill="#FFEBD6"/>
      </g>
    </svg>
  );
}

function ConstellationLight({ className = '', style = {} }) {
  const pathRef = useRef(null);
  const blueRef = useRef(null);
  const redRef = useRef(null);
  const orangeRef = useRef(null);

  useEffect(() => {
    const loopPath = pathRef.current;
    if (!loopPath) return;

    const totalLength = loopPath.getTotalLength();
    if (!totalLength) return;

    const dots = [
      { ref: blueRef, offset: 0.0, speedMult: 1.0 },
      { ref: redRef, offset: 0.34, speedMult: 0.85 },
      { ref: orangeRef, offset: 0.67, speedMult: 1.15 },
    ];

    let baseSpeed = 7;
    let lastTime = null;
    let elapsed = 0;
    let animationFrameId;

    const frame = (timestamp) => {
      if (lastTime === null) lastTime = timestamp;
      const dt = (timestamp - lastTime) / 1000;
      lastTime = timestamp;
      elapsed += dt;

      dots.forEach(dot => {
        const cycleTime = baseSpeed / dot.speedMult;
        let progress = ((elapsed / cycleTime) + dot.offset) % 1;
        const point = loopPath.getPointAtLength(progress * totalLength);
        if (dot.ref.current) {
          dot.ref.current.setAttribute('transform', `translate(${point.x}, ${point.y})`);
        }
      });
      animationFrameId = requestAnimationFrame(frame);
    };

    animationFrameId = requestAnimationFrame(frame);
    return () => cancelAnimationFrame(animationFrameId);
  }, []);

  return (
    <svg viewBox="0 0 226 226" className={`hidden dark:inline-block ${className}`} style={style} aria-hidden="true">
      <defs>
        <filter id="dotBlurLight" x="-300%" y="-300%" width="700%" height="700%">
          <feGaussianBlur stdDeviation="5" result="blur"/>
          <feMerge><feMergeNode in="blur"/><feMergeNode in="blur"/></feMerge>
        </filter>
      </defs>

      <path className="constellation-line-light" d="M 168 47 A 80 80 0 1 0 63 187"/>
      <path className="constellation-line-light" d="M 73 193 A 80 80 0 0 0 165 52"/>

      <path className="constellation-line-light" d="M 60 110 C 87 100, 100 117, 123 107 C 143 98, 153 86, 168 47"/>
      <path className="constellation-line-light" d="M 53 143 C 80 133, 107 150, 133 137 C 153 127, 163 110, 168 47"/>
      <path className="constellation-line-light" d="M 72 188 C 93 173, 110 180, 127 167 C 147 152, 157 130, 165 52"/>
      <path className="constellation-line-light" d="M 87 203 C 107 190, 123 197, 143 178 C 163 161, 165 133, 165 52"/>
      <path className="constellation-line-light" d="M 63 187 C 100 173, 116 193, 147 170 C 165 154, 165 133, 165 52"/>

      <path ref={pathRef}
            d="M 168 47 A 80 80 0 1 0 63 187 C 100 173, 116 193, 147 170 C 165 154, 167 120, 168 47"
            fill="none" stroke="none"/>

      <circle className="constellation-node-dot-light" cx="63" cy="187" r="11"/>
      <circle className="constellation-node-dot-light" cx="168" cy="47" r="9.5"/>

      <g ref={blueRef}>
        <circle className="constellation-glow-dot-halo-light" r="9" fill="#3B6FD4"/>
        <circle className="constellation-glow-dot-core" r="3.4" fill="#F0F5FF"/>
      </g>
      <g ref={redRef}>
        <circle className="constellation-glow-dot-halo-light" r="9" fill="#FF5C5C"/>
        <circle className="constellation-glow-dot-core" r="3.4" fill="#FFE3E3"/>
      </g>
      <g ref={orangeRef}>
        <circle className="constellation-glow-dot-halo-light" r="9" fill="#FFA64D"/>
        <circle className="constellation-glow-dot-core" r="3.4" fill="#FFEBD6"/>
      </g>
    </svg>
  );
}

import { useTheme } from './../context/ThemeContext.jsx';

export default function Constellation({ className = '', style = {}, invert = false }) {
  const { theme } = useTheme();
  
  const effectiveTheme = invert ? (theme === 'dark' ? 'light' : 'dark') : theme;

  if (effectiveTheme === 'dark') {
    return <ConstellationLight className={className} style={style} />;
  }
  
  return <ConstellationDark className={className} style={style} />;
}
