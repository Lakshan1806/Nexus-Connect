import { useState, useEffect, useCallback } from 'react';
import { sendFileTransfer, getUserTransfers, listDownloads, getDownloadUrl } from '../api';

function FileTransferModal({ isOpen, onClose, selectedPeer, session }) {
  const [filePath, setFilePath] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [transfers, setTransfers] = useState([]);
  const [loadingTransfers, setLoadingTransfers] = useState(false);
  const [isDragging, setIsDragging] = useState(false);
  const [receivedFiles, setReceivedFiles] = useState([]);
  const [loadingFiles, setLoadingFiles] = useState(false);
  const [activeTab, setActiveTab] = useState('send'); // 'send' or 'received'

  const handleFileSelect = (e) => {
    const file = e.target.files?.[0];
    if (file) {
      // Try to get the full path - webkitRelativePath or path property (in Electron/Node environments)
      // In regular browsers, we can only get the filename for security reasons
      const fullPath = file.path || file.webkitRelativePath || file.name;
      
      // Check if we got a full path or just filename
      if (fullPath.includes('\\') || fullPath.includes('/')) {
        // We have a full path
        setFilePath(fullPath);
        setError('');
        setSuccess('File selected: ' + file.name);
      } else {
        // Browser security limitation - only got filename
        // Try to construct a likely path
        setFilePath(fullPath);
        setError('');
        setSuccess('File selected. If path is incomplete, please enter the full path manually.');
      }
    }
  };

  const handleDragOver = (e) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragging(true);
  };

  const handleDragLeave = (e) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragging(false);
  };

  const handleDrop = (e) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragging(false);

    const file = e.dataTransfer.files?.[0];
    if (file) {
      // Try to get the full path from the dropped file
      const fullPath = file.path || file.webkitRelativePath || file.name;
      
      if (fullPath.includes('\\') || fullPath.includes('/')) {
        setFilePath(fullPath);
        setError('');
        setSuccess('File dropped: ' + file.name);
      } else {
        setFilePath(fullPath);
        setError('');
        setSuccess('File dropped. If path is incomplete, please enter the full path manually.');
      }
    }
  };

  const loadTransfers = useCallback(async () => {
    if (!session?.user) return;
    
    setLoadingTransfers(true);
    try {
      const userTransfers = await getUserTransfers(session.user);
      setTransfers(Array.isArray(userTransfers) ? userTransfers : []);
    } catch (err) {
      console.error('Failed to load transfers:', err);
    } finally {
      setLoadingTransfers(false);
    }
  }, [session]);

  const loadReceivedFiles = useCallback(async () => {
    setLoadingFiles(true);
    try {
      const files = await listDownloads();
      setReceivedFiles(Array.isArray(files) ? files : []);
    } catch (err) {
      console.error('Failed to load received files:', err);
    } finally {
      setLoadingFiles(false);
    }
  }, []);

  useEffect(() => {
    if (isOpen) {
      loadTransfers();
      loadReceivedFiles();
      
      // Auto-refresh transfers and files every 3 seconds
      const interval = setInterval(() => {
        loadTransfers();
        loadReceivedFiles();
      }, 3000);
      return () => clearInterval(interval);
    }
  }, [isOpen, loadTransfers, loadReceivedFiles]);

  const handleSendFile = async (e) => {
    e.preventDefault();
    
    if (!filePath.trim()) {
      setError('Please enter a file path');
      return;
    }

    if (!selectedPeer) {
      setError('No peer selected');
      return;
    }

    // Check if peer has file transfer port (handle both fileTcp and fileTcp property)
    const peerPort = selectedPeer.fileTcp;
    if (!peerPort || peerPort <= 0) {
      setError('Selected peer does not have file transfer enabled (no port advertised)');
      return;
    }

    setSending(true);
    setError('');
    setSuccess('');

    try {
      // Normalize the file path - replace forward slashes with backslashes for Windows
      // The backend expects Windows-style paths
      let normalizedPath = filePath.trim();
      
      // Convert forward slashes to backslashes if present
      normalizedPath = normalizedPath.replace(/\//g, '\\');
      
      const result = await sendFileTransfer({
        peerIp: selectedPeer.ip,
        peerPort: selectedPeer.fileTcp,
        filePath: normalizedPath,
        senderUsername: session.user
      });

      if (result.success) {
        setSuccess(`File transfer initiated: ${result.filename}`);
        setFilePath('');
        loadTransfers();
      } else {
        setError(result.message || 'Failed to send file');
      }
    } catch (err) {
      console.error('File transfer failed:', err);
      setError(err.message || 'Failed to initiate file transfer');
    } finally {
      setSending(false);
    }
  };

  const formatBytes = (bytes) => {
    if (bytes === 0) return '0 Bytes';
    const k = 1024;
    const sizes = ['Bytes', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return Math.round((bytes / Math.pow(k, i)) * 100) / 100 + ' ' + sizes[i];
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
      <div className="relative w-full max-w-3xl max-h-[90vh] overflow-y-auto rounded-3xl border border-white/10 bg-slate-900/95 p-8 shadow-2xl">
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
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
            </svg>
            <h2 className="text-2xl font-semibold text-slate-50">P2P File Transfer</h2>
          </div>
          <p className="mt-2 text-sm text-slate-400">
            Send files directly to peers using TCP socket connection
          </p>
        </div>

        {/* Tabs */}
        <div className="mb-6 flex gap-2 border-b border-white/10">
          <button
            onClick={() => setActiveTab('send')}
            className={`px-4 py-2 font-medium transition ${
              activeTab === 'send'
                ? 'border-b-2 border-brand-400 text-brand-400'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            Send File
          </button>
          <button
            onClick={() => setActiveTab('received')}
            className={`px-4 py-2 font-medium transition ${
              activeTab === 'received'
                ? 'border-b-2 border-brand-400 text-brand-400'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            Received Files {receivedFiles.length > 0 && `(${receivedFiles.length})`}
          </button>
        </div>

        {/* Send File Tab */}
        {activeTab === 'send' && (
        <>
        <div className="mb-6 rounded-2xl border border-white/10 bg-slate-950/70 p-6">
          <h3 className="text-lg font-semibold text-slate-200 mb-4">Send File</h3>
          
          {selectedPeer && (
            <div className="mb-4 rounded-xl border border-blue-500/20 bg-blue-500/5 p-3">
              <p className="text-sm text-blue-200">
                <strong>Recipient:</strong> {selectedPeer.user} @ {selectedPeer.ip}:{selectedPeer.fileTcp || 'no port'}
              </p>
              {(!selectedPeer.fileTcp || selectedPeer.fileTcp <= 0) && (
                <p className="mt-2 text-xs text-amber-300">
                  ⚠️ This peer has not advertised a file transfer port. Enter port manually in login or send anyway.
                </p>
              )}
            </div>
          )}

          {!selectedPeer && (
            <div className="mb-4 rounded-xl border border-amber-500/20 bg-amber-500/5 p-3">
              <p className="text-sm text-amber-200">
                ⚠️ No peer selected. Please select a peer from the user list first.
              </p>
            </div>
          )}

          <form onSubmit={handleSendFile} className="space-y-4">
            <div>
              <label className="text-sm font-medium text-slate-200" htmlFor="filePath">
                File Path
              </label>
              
              {/* Drag and drop zone with file input */}
              <div 
                className={`mt-2 rounded-xl border-2 border-dashed transition-colors ${
                  isDragging 
                    ? 'border-brand-400 bg-brand-500/10' 
                    : 'border-white/10 bg-slate-900/40'
                }`}
                onDragOver={handleDragOver}
                onDragLeave={handleDragLeave}
                onDrop={handleDrop}
              >
                <div className="flex gap-2 p-4">
                  <label className="flex-shrink-0 cursor-pointer inline-flex items-center gap-2 rounded-xl border border-white/10 bg-slate-800/80 px-4 py-3 text-sm font-medium text-slate-300 transition hover:bg-slate-700/80 hover:text-white">
                    <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z" />
                    </svg>
                    Browse
                    <input
                      type="file"
                      onChange={handleFileSelect}
                      className="hidden"
                    />
                  </label>
                  <input
                    id="filePath"
                    type="text"
                    value={filePath}
                    onChange={(e) => setFilePath(e.target.value)}
                    placeholder="C:\\Users\\Admin\\Documents\\file.pdf"
                    className="focus:border-brand-400 focus:ring-brand-400/60 flex-1 rounded-xl border border-white/10 bg-slate-900/80 px-4 py-3 text-base text-slate-100 placeholder:text-slate-500 focus:ring-2 focus:outline-none"
                  />
                </div>
                <div className="px-4 pb-3 text-center">
                  <p className="text-xs text-slate-500">
                    {isDragging ? (
                      <span className="text-brand-400 font-medium">Drop file here...</span>
                    ) : (
                      <>Drag & drop a file here, browse, or type the full path</>
                    )}
                  </p>
                </div>
              </div>
              <p className="mt-1 text-xs text-slate-500">
                Use forward slashes or double backslashes in paths
              </p>
              <p className="mt-1 text-xs text-slate-400">
                Example: C:/Users/Admin/Desktop/file.pdf or C:\\Users\\Admin\\Desktop\\file.pdf
              </p>
            </div>

            {error && (
              <div className="rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-200">
                {error}
              </div>
            )}

            {success && (
              <div className="rounded-xl border border-emerald-500/40 bg-emerald-500/10 px-4 py-3 text-sm text-emerald-200">
                {success}
              </div>
            )}

            <button
              type="submit"
              disabled={sending || !selectedPeer || !filePath.trim()}
              className="bg-brand-500 hover:bg-brand-400 disabled:bg-brand-500/60 w-full rounded-xl px-4 py-3 text-base font-semibold text-white transition disabled:cursor-not-allowed"
              title={
                sending ? 'Sending file...' :
                !selectedPeer ? 'Please select a peer first' :
                !filePath.trim() ? 'Please enter a file path' :
                'Click to send file'
              }
            >
              {sending ? 'Sending...' : 'Send File'}
            </button>
            
            {/* Debug info */}
            <div className="text-xs text-slate-500 mt-2">
              <p>Debug: Peer selected: {selectedPeer ? '✓ Yes' : '✗ No'}</p>
              <p>Debug: File path filled: {filePath.trim() ? '✓ Yes' : '✗ No'} ({filePath.length} chars)</p>
              <p>Debug: Button enabled: {!sending && selectedPeer && filePath.trim() ? '✓ Yes' : '✗ No'}</p>
            </div>
          </form>
        </div>

        {/* Active Transfers */}
        <div className="rounded-2xl border border-white/10 bg-slate-950/70 p-6">
          <div className="flex items-center justify-between mb-4">
            <h3 className="text-lg font-semibold text-slate-200">Active Transfers</h3>
            <button
              onClick={loadTransfers}
              disabled={loadingTransfers}
              className="text-sm text-brand-400 hover:text-brand-300 transition"
            >
              {loadingTransfers ? 'Refreshing...' : 'Refresh'}
            </button>
          </div>

          <div className="space-y-3 max-h-64 overflow-y-auto">
            {transfers.length === 0 ? (
              <div className="flex h-32 items-center justify-center text-sm text-slate-500">
                No active transfers
              </div>
            ) : (
              transfers.map((transfer) => (
                <div
                  key={transfer.transferId}
                  className="rounded-xl border border-white/10 bg-slate-900/60 p-4"
                >
                  <div className="flex items-start justify-between">
                    <div className="flex-1 min-w-0">
                      <p className="font-semibold text-slate-100 truncate">
                        {transfer.filename}
                      </p>
                      <p className="text-xs text-slate-400 mt-1">
                        {transfer.isReceiving ? 'From' : 'To'}: {transfer.isReceiving ? transfer.sender : transfer.receiver}
                      </p>
                      <p className="text-xs text-slate-500">
                        {formatBytes(transfer.bytesTransferred)} / {formatBytes(transfer.totalBytes)} 
                        {transfer.speedMBps > 0 && ` • ${transfer.speedMBps.toFixed(2)} MB/s`}
                      </p>
                    </div>
                    <div className="ml-3">
                      {transfer.completed ? (
                        <span className="rounded-full bg-emerald-500/10 px-3 py-1 text-xs text-emerald-300">
                          Complete
                        </span>
                      ) : transfer.failed ? (
                        <span className="rounded-full bg-red-500/10 px-3 py-1 text-xs text-red-300">
                          Failed
                        </span>
                      ) : (
                        <span className="rounded-full bg-blue-500/10 px-3 py-1 text-xs text-blue-300">
                          {transfer.progressPercent}%
                        </span>
                      )}
                    </div>
                  </div>
                  
                  {!transfer.completed && !transfer.failed && (
                    <div className="mt-3 h-2 w-full overflow-hidden rounded-full bg-slate-700">
                      <div
                        className="h-full bg-brand-500 transition-all duration-300"
                        style={{ width: `${transfer.progressPercent}%` }}
                      />
                    </div>
                  )}

                  {transfer.failed && transfer.errorMessage && (
                    <p className="mt-2 text-xs text-red-300">{transfer.errorMessage}</p>
                  )}
                </div>
              ))
            )}
          </div>
        </div>
        </>
        )}

        {/* Received Files Tab */}
        {activeTab === 'received' && (
        <div className="rounded-2xl border border-white/10 bg-slate-950/70 p-6">
          <div className="flex items-center justify-between mb-4">
            <h3 className="text-lg font-semibold text-slate-200">Received Files</h3>
            <button
              onClick={loadReceivedFiles}
              disabled={loadingFiles}
              className="text-sm text-brand-400 hover:text-brand-300 transition"
            >
              {loadingFiles ? 'Refreshing...' : 'Refresh'}
            </button>
          </div>

          <div className="space-y-3 max-h-96 overflow-y-auto">
            {receivedFiles.length === 0 ? (
              <div className="flex h-32 items-center justify-center text-sm text-slate-500">
                No received files yet
              </div>
            ) : (
              receivedFiles.map((file, index) => (
                <div
                  key={index}
                  className="rounded-xl border border-white/10 bg-slate-900/60 p-4 hover:bg-slate-900/80 transition"
                >
                  <div className="flex items-start justify-between gap-4">
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2">
                        <svg className="h-5 w-5 text-emerald-400 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                        </svg>
                        <p className="font-semibold text-slate-100 truncate">
                          {file.filename}
                        </p>
                      </div>
                      <div className="mt-2 flex items-center gap-4 text-xs text-slate-400">
                        <span>{formatBytes(file.size)}</span>
                        <span>{new Date(file.lastModified).toLocaleString()}</span>
                      </div>
                      <p className="mt-1 text-xs text-slate-500 truncate" title={file.path}>
                        {file.path}
                      </p>
                    </div>
                    <div className="flex gap-2 shrink-0">
                      <a
                        href={getDownloadUrl(file.filename)}
                        download={file.filename}
                        className="rounded-lg bg-brand-500 hover:bg-brand-400 px-4 py-2 text-sm font-medium text-white transition flex items-center gap-2"
                      >
                        <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" />
                        </svg>
                        Download
                      </a>
                      <button
                        onClick={() => {
                          // Open file in new tab for preview
                          window.open(getDownloadUrl(file.filename), '_blank');
                        }}
                        className="rounded-lg bg-slate-700 hover:bg-slate-600 px-4 py-2 text-sm font-medium text-slate-200 transition flex items-center gap-2"
                        title="Open/Preview file"
                      >
                        <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
                        </svg>
                        Open
                      </button>
                    </div>
                  </div>
                </div>
              ))
            )}
          </div>
        </div>
        )}

        <div className="mt-6 rounded-2xl border border-blue-500/20 bg-blue-500/5 p-4">
          <div className="flex gap-3">
            <svg className="h-5 w-5 shrink-0 text-blue-400" fill="currentColor" viewBox="0 0 20 20">
              <path fillRule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" clipRule="evenodd" />
            </svg>
            <div className="text-xs text-blue-200">
              <p><strong>How it works:</strong> Files are sent directly to the peer using TCP socket connection on their advertised port. Both users must have their file transfer ports accessible. Files are saved to the <code className="bg-blue-500/20 px-1 rounded">nexus_downloads</code> directory.</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

export default FileTransferModal;
