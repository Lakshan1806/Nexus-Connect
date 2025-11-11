import { useState, useEffect, useCallback } from 'react';
import { broadcastDiscovery, fetchDiscoveredPeers } from '../api';

function LANDiscovery({ username, isOpen, onClose }) {
  const [peers, setPeers] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [scanning, setScanning] = useState(false);

  const loadPeers = useCallback(async () => {
    try {
      const discoveredPeers = await fetchDiscoveredPeers();
      setPeers(Array.isArray(discoveredPeers) ? discoveredPeers : []);
      setError('');
    } catch (err) {
      console.error('Failed to load peers:', err);
      setError('Failed to load discovered peers');
    }
  }, []);

  const handleScanNetwork = async () => {
    if (scanning || !username) return;
    
    setScanning(true);
    setError('');
    setLoading(true);

    try {
      // Broadcast discovery request
      await broadcastDiscovery(username, 'Scanning LAN');
      
      // Wait for responses to come in
      await new Promise(resolve => setTimeout(resolve, 2000));
      
      // Load discovered peers
      await loadPeers();
      
    } catch (err) {
      console.error('Network scan failed:', err);
      setError(err.message || 'Failed to scan network');
    } finally {
      setScanning(false);
      setLoading(false);
    }
  };

  useEffect(() => {
    if (isOpen) {
      loadPeers();
      
      // Auto-refresh peers every 10 seconds
      const interval = setInterval(loadPeers, 10000);
      return () => clearInterval(interval);
    }
  }, [isOpen, loadPeers]);

  const formatLastSeen = (timestamp) => {
    const seconds = Math.floor((Date.now() - timestamp) / 1000);
    if (seconds < 60) return `${seconds}s ago`;
    if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
    return `${Math.floor(seconds / 3600)}h ago`;
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
      <div className="relative w-full max-w-2xl rounded-3xl border border-white/10 bg-slate-900/95 p-8 shadow-2xl">
        <button
          onClick={onClose}
          className="absolute right-6 top-6 rounded-full p-2 text-slate-400 transition hover:bg-white/10 hover:text-white"
          aria-label="Close"
        >
          <svg className="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>

        <div className="mb-6">
          <div className="flex items-center gap-3">
            <svg className="h-8 w-8 text-brand-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 12a9 9 0 01-9 9m9-9a9 9 0 00-9-9m9 9H3m9 9a9 9 0 01-9-9m9 9c1.657 0 3-4.03 3-9s-1.343-9-3-9m0 18c-1.657 0-3-4.03-3-9s1.343-9 3-9m-9 9a9 9 0 019-9" />
            </svg>
            <h2 className="text-2xl font-semibold text-slate-50">LAN Peer Discovery</h2>
          </div>
          <p className="mt-2 text-sm text-slate-400">
            Find and connect with nearby peers on your local network using UDP broadcast
          </p>
        </div>

        <div className="mb-6 flex items-center justify-between rounded-2xl border border-white/10 bg-slate-950/70 p-4">
          <div className="text-sm">
            <p className="font-semibold text-slate-200">
              {peers.length} {peers.length === 1 ? 'peer' : 'peers'} discovered
            </p>
            <p className="text-xs text-slate-500">Broadcasting on UDP port 9876</p>
          </div>
          <button
            onClick={handleScanNetwork}
            disabled={scanning || !username}
            className="bg-brand-500 hover:bg-brand-400 disabled:bg-brand-500/60 inline-flex items-center gap-2 rounded-xl px-4 py-2 text-sm font-semibold text-white transition disabled:cursor-not-allowed"
          >
            {scanning ? (
              <>
                <svg className="h-5 w-5 animate-spin" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                </svg>
                <span>Scanning...</span>
              </>
            ) : (
              <>
                <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
                </svg>
                <span>Scan Network</span>
              </>
            )}
          </button>
        </div>

        {error && (
          <div className="mb-4 rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-200">
            {error}
          </div>
        )}

        <div className="max-h-96 space-y-3 overflow-y-auto pr-2">
          {loading && peers.length === 0 ? (
            <div className="flex h-40 items-center justify-center text-sm text-slate-500">
              <div className="flex items-center gap-2">
                <svg className="h-5 w-5 animate-spin" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                </svg>
                <span>Discovering peers...</span>
              </div>
            </div>
          ) : peers.length === 0 ? (
            <div className="flex h-40 flex-col items-center justify-center gap-3 text-center">
              <svg className="h-12 w-12 text-slate-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M9.172 16.172a4 4 0 015.656 0M9 10h.01M15 10h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              <div>
                <p className="text-sm font-medium text-slate-400">No peers discovered yet</p>
                <p className="mt-1 text-xs text-slate-600">Click "Scan Network" to discover nearby peers</p>
              </div>
            </div>
          ) : (
            peers.map((peer) => (
              <div
                key={peer.username}
                className={`rounded-2xl border px-4 py-3 transition ${
                  peer.isStale
                    ? 'border-white/5 bg-slate-950/40 opacity-50'
                    : 'border-white/10 bg-slate-950/70'
                }`}
              >
                <div className="flex items-start justify-between">
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-3">
                      <div className="bg-brand-500/20 flex h-10 w-10 shrink-0 items-center justify-center rounded-full text-sm font-semibold text-brand-200">
                        {peer.username.slice(0, 2).toUpperCase()}
                      </div>
                      <div className="min-w-0">
                        <p className="truncate font-semibold text-slate-100">{peer.username}</p>
                        <p className="truncate text-xs text-slate-400">
                          {peer.ipAddress}
                          {peer.isStale && ' • Offline'}
                        </p>
                      </div>
                    </div>
                    {peer.additionalInfo && (
                      <p className="mt-2 text-xs text-slate-500">{peer.additionalInfo}</p>
                    )}
                  </div>
                  <div className="ml-3 flex shrink-0 flex-col items-end gap-1">
                    <span className={`rounded-full px-2 py-1 text-xs ${
                      peer.isStale 
                        ? 'bg-slate-700/50 text-slate-500'
                        : 'bg-emerald-500/10 text-emerald-300'
                    }`}>
                      {peer.isStale ? 'Offline' : 'Online'}
                    </span>
                    <span className="text-xs text-slate-600">
                      {formatLastSeen(peer.lastSeen)}
                    </span>
                  </div>
                </div>
              </div>
            ))
          )}
        </div>

        <div className="mt-6 rounded-2xl border border-blue-500/20 bg-blue-500/5 p-4">
          <div className="flex gap-3">
            <svg className="h-5 w-5 shrink-0 text-blue-400" fill="currentColor" viewBox="0 0 20 20">
              <path fillRule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" clipRule="evenodd" />
            </svg>
            <p className="text-xs text-blue-200">
              <strong>How it works:</strong> UDP broadcasting sends discovery packets to all devices on your local network.
              Peers that respond will appear in this list. This feature works best on trusted networks.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}

export default LANDiscovery;