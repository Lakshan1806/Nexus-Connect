import PeerRow from "./PeerRow.jsx";

function PeerCard({ peer, peerError, peerLoading, selectedUser, session }) {
  const isSelf = selectedUser.user === session.user;
  return (
    <div className="rounded-2xl border border-white/10 bg-slate-950/70 p-4 text-sm text-slate-300">
      <h3 className="text-xs font-semibold tracking-[0.2em] text-slate-400 uppercase">
        Peer card
      </h3>
      <p className="mt-2 text-base font-semibold text-slate-100">
        {selectedUser.user}
      </p>
      {isSelf ? (
        <p className="mt-2 text-xs text-slate-500">
          This is you. Advertise your ports during login so peers can reach you
          directly.
        </p>
      ) : peerLoading ? (
        <p className="mt-2 text-xs text-slate-500">
          Fetching live peer details…
        </p>
      ) : peerError ? (
        <p className="mt-2 text-xs text-amber-300">{peerError}</p>
      ) : peer ? (
        <dl className="mt-3 space-y-2">
          <PeerRow label="IP" value={peer.ip || "Not shared"} />
          <PeerRow
            label="File TCP"
            value={peer.fileTcp > 0 ? peer.fileTcp : "Not advertised"}
          />
          <PeerRow
            label="Voice UDP"
            value={peer.voiceUdp > 0 ? peer.voiceUdp : "Not advertised"}
          />
          <PeerRow
            label="Gateway"
            value={peer.viaNio ? "Direct NIO socket" : "HTTP bridge"}
          />
        </dl>
      ) : (
        <p className="mt-2 text-xs text-slate-500">
          No peer details available yet.
        </p>
      )}
    </div>
  );
}

export default PeerCard;