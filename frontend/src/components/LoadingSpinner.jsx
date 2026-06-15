import React from 'react';
import './LoadingSpinner.css';

const LoadingSpinner = ({ size = 'medium', message = 'Loading...' }) => {
  return (
    <div className="loading-spinner-wrapper">
      <div className={`loading-spinner ${size}`}>
        <div className="spinner"></div>
      </div>
      <p>{message}</p>
    </div>
  );
};

export default LoadingSpinner;
