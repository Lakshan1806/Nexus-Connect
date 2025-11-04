function NumberInput({ id, label, placeholder, value, onChange }) {
  return (
    <div className="space-y-2">
      <label className="text-sm font-medium text-slate-200" htmlFor={id}>
        {label}
      </label>
      <input
        id={id}
        type="number"
        inputMode="numeric"
        min="0"
        value={value}
        onChange={onChange}
        className="focus:border-brand-400 focus:ring-brand-400/60 w-full rounded-xl border border-white/10 bg-slate-900/80 px-4 py-3 text-base text-slate-100 placeholder:text-slate-500 focus:ring-2 focus:outline-none"
        placeholder={placeholder}
      />
    </div>
  );
}

export default NumberInput;