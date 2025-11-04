
function PeerRow({ label, value }) {
  return (
    <div className="flex items-center justify-between gap-4">
      <dt className="text-xs tracking-wide text-slate-500 uppercase">
        {label}
      </dt>
      <dd className="text-sm text-slate-200">{value}</dd>
    </div>
  );
}

export default PeerRow;