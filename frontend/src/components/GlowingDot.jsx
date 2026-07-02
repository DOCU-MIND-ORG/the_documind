import React, { useRef, useEffect, useMemo } from 'react';

function hslToRgb(h, s, l) {
  s /= 100;
  l /= 100;
  const a = s * Math.min(l, 1 - l);
  const f = n => {
    const k = (n + h / 30) % 12;
    const color = l - a * Math.max(-1, Math.min(k - 3, 9 - k, 1));
    return Math.round(255 * color);
  };
  return { r: f(0), g: f(8), b: f(4) };
}

export default function GlowingDot({ sessionId }) {
  const canvasRef = useRef(null);

  // Generate a deterministic color based on the sessionId
  const { r, g, b } = useMemo(() => {
    let hash = 0;
    const str = String(sessionId || 'default');
    for (let i = 0; i < str.length; i++) {
      hash = str.charCodeAt(i) + ((hash << 5) - hash);
    }
    
    // Mix the hash heavily so sequential IDs (like 1, 2, 3) get completely different colors
    hash = Math.imul(hash ^ (hash >>> 15), 0x85ebca6b);
    hash = Math.imul(hash ^ (hash >>> 13), 0xc2b2ae35);
    hash = hash ^ (hash >>> 16);

    const h = Math.abs(hash) % 360;
    const s = 80 + (Math.abs(hash >> 8) % 20);
    const l = 50 + (Math.abs(hash >> 16) % 15);
    return hslToRgb(h, s, l);
  }, [sessionId]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    let frame = 0;
    let animationId;
    
    // Scale down the canvas and radii to fit neatly into a sidebar icon slot
    const canvasSize = 20;
    canvas.width = canvasSize;
    canvas.height = canvasSize;
    
    const glowRadius = 4.5;
    const dotRadius = 2.2;
    const cx = canvas.width / 2;
    const cy = canvas.height / 2;

    function drawFrame() {
      ctx.clearRect(0, 0, canvas.width, canvas.height);

      // Subtle breathing pulse
      const pulse = 1 + 0.3 * Math.sin(frame * 0.05);
      const gr = glowRadius * pulse;

      // Layer 1 — wide outer halo
      const outer = ctx.createRadialGradient(cx, cy, 0, cx, cy, gr * 3.5);
      outer.addColorStop(0,   `rgba(${r},${g},${b},0.35)`);
      outer.addColorStop(0.4, `rgba(${r},${g},${b},0.12)`);
      outer.addColorStop(1,   `rgba(${r},${g},${b},0)`);
      ctx.beginPath();
      ctx.arc(cx, cy, gr * 3.5, 0, Math.PI * 2);
      ctx.fillStyle = outer;
      ctx.fill();

      // Layer 2 — inner bloom
      const bloom = ctx.createRadialGradient(cx, cy, 0, cx, cy, gr * 1.5);
      bloom.addColorStop(0,   `rgba(${r},${g},${b},0.70)`);
      bloom.addColorStop(0.5, `rgba(${r},${g},${b},0.30)`);
      bloom.addColorStop(1,   `rgba(${r},${g},${b},0)`);
      ctx.beginPath();
      ctx.arc(cx, cy, gr * 1.5, 0, Math.PI * 2);
      ctx.fillStyle = bloom;
      ctx.fill();

      // Layer 3 — core dot
      const core = ctx.createRadialGradient(
        cx - dotRadius * 0.25, cy - dotRadius * 0.25, 0,
        cx, cy, dotRadius
      );
      core.addColorStop(0,   `rgba(255,255,255,0.95)`);
      core.addColorStop(0.4, `rgba(${r},${g},${b},1)`);
      core.addColorStop(1,   `rgba(${r},${g},${b},0.85)`);
      ctx.beginPath();
      ctx.arc(cx, cy, dotRadius, 0, Math.PI * 2);
      ctx.fillStyle = core;
      ctx.fill();

      // Layer 4 — specular highlight
      const spec = ctx.createRadialGradient(
        cx - dotRadius * 0.3, cy - dotRadius * 0.35, 0,
        cx - dotRadius * 0.3, cy - dotRadius * 0.35, dotRadius * 0.55
      );
      spec.addColorStop(0, 'rgba(255,255,255,0.70)');
      spec.addColorStop(1, 'rgba(255,255,255,0)');
      ctx.beginPath();
      ctx.arc(cx, cy, dotRadius, 0, Math.PI * 2);
      ctx.fillStyle = spec;
      ctx.fill();

      frame++;
      animationId = requestAnimationFrame(drawFrame);
    }

    drawFrame();
    return () => cancelAnimationFrame(animationId);
  }, [r, g, b]);

  return <canvas ref={canvasRef} className="shrink-0 w-[18px] h-[18px] mr-[1px] ml-[1px]" />;
}
