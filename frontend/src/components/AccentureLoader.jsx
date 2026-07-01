import React, { useEffect, useRef } from 'react';
import './AccentureLoader.css';

export default function AccentureLoader({ className, style }) {
  const wordRef = useRef(null);
  const arrowRef = useRef(null);

  useEffect(() => {
    let tids = [];
    const after = (ms, fn) => tids.push(setTimeout(fn, ms));
    const clearAll = () => { tids.forEach(clearTimeout); tids = []; };

    const reset = () => {
      clearAll();
      if (wordRef.current) wordRef.current.classList.remove('run');
      if (arrowRef.current) arrowRef.current.classList.remove('run');
      // Force DOM reflow
      if (wordRef.current) void wordRef.current.offsetWidth;
    };

    const play = () => {
      reset();
      after(30, () => {
        if (wordRef.current) wordRef.current.classList.add('run');
        if (arrowRef.current) arrowRef.current.classList.add('run');
      });
    };

    play();
    const intervalId = setInterval(play, 3000); // loop every 3 seconds

    return () => {
      clearAll();
      clearInterval(intervalId);
    };
  }, []);

  return (
    <div className={`kinetic-stage ${className || ''}`} style={style}>
      <div className="kinetic-wrap">
        <span className="kinetic-word" ref={wordRef}>accenture</span>
        <img 
          src="/logoaccenture.png" 
          alt="Accenture Logo"
          className="kinetic-arrow" 
          ref={arrowRef} 
        />
      </div>
    </div>
  );
}