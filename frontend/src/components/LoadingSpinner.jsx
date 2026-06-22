const LoadingSpinner = ({ size = 'medium', message = 'Loading...' }) => {
  const sizeClasses = {
    small: 'w-[30px] h-[30px] border-2',
    medium: 'w-[50px] h-[50px] border-4',
    large: 'w-[80px] h-[80px] border-[5px]',
  };

  return (
    <div className="flex flex-col items-center justify-center gap-3 p-6">
      <div className={`border-white/10 border-t-blue-500 rounded-full animate-spin ${sizeClasses[size] || sizeClasses.medium}`}></div>
      {message && <p className="text-sm text-[#94a3b8]">{message}</p>}
    </div>
  );
};

export default LoadingSpinner;
