import axios from 'axios'

const inferApiBase = () => {
  if (typeof window === 'undefined') {
    return 'http://localhost:8080'
  }
  const { protocol, hostname, port } = window.location
  if (port === '5173' || port === '4173') {
    const targetPort = protocol === 'https:' ? '8443' : '8080'
    return `${protocol}//${hostname}:${targetPort}`
  }
  return `${protocol}//${hostname}${port ? `:${port}` : ''}`
}

const envBase = (import.meta.env.VITE_API_BASE ?? '').trim()
export const API_BASE = envBase || inferApiBase()

const client = axios.create({
  baseURL: API_BASE,
})

let authToken = ''

export function setAuthToken(token) {
  authToken = token ?? ''
  if (authToken) {
    client.defaults.headers.common.Authorization = `Bearer ${authToken}`
    axios.defaults.headers.common.Authorization = `Bearer ${authToken}`
  } else {
    delete client.defaults.headers.common.Authorization
    delete axios.defaults.headers.common.Authorization
  }
}

async function request(path, { method = 'GET', json, headers } = {}) {
  try {
    const config = {
      url: path,
      method,
      headers: {
        ...(headers ?? {}),
      },
    }
    if (json !== undefined) {
      config.data = json
      config.headers['Content-Type'] = 'application/json'
    }
    const response = await client.request(config)
    return response.status === 204 ? null : response.data
  } catch (error) {
    if (error.response) {
      const { status, statusText, data } = error.response
      const reason = data?.reason || data?.message || statusText || error.message || 'Request failed'
      const err = new Error(reason)
      err.status = status
      err.payload = data
      throw err
    }
    throw error
  }
}

// Authentication -------------------------------------------------------------

export async function signUp({ name, email, password }) {
  return request('/api/auth/register', {
    method: 'POST',
    json: { name, email, password },
  })
}

export async function signIn({ email, password }) {
  return request('/api/auth/login', {
    method: 'POST',
    json: { email, password },
  })
}

export async function fetchCurrentUser() {
  return request('/api/auth/me')
}

// Lobby / chat ---------------------------------------------------------------

export async function connectToLobby({ fileTcp, voiceUdp, ipOverride }) {
  const payload = {}
  if (fileTcp !== undefined && fileTcp !== '') payload.fileTcp = Number(fileTcp)
  if (voiceUdp !== undefined && voiceUdp !== '') payload.voiceUdp = Number(voiceUdp)
  if (ipOverride) payload.ipOverride = ipOverride
  return request('/api/nio/login', { method: 'POST', json: payload })
}

export async function disconnectFromLobby() {
  return request('/api/nio/logout', { method: 'POST' })
}

export async function fetchSnapshot() {
  const [users, messages] = await Promise.all([
    request('/api/nio/users'),
    request('/api/nio/messages'),
  ])
  return {
    users: Array.isArray(users) ? users : [],
    messages: Array.isArray(messages) ? messages : [],
  }
}

export async function sendChat(text) {
  return request('/api/nio/message', { method: 'POST', json: { text } })
}

export async function fetchPeerDetails(user) {
  if (!user) return null
  try {
    return await request(`/api/nio/peer/${encodeURIComponent(user)}`)
  } catch (error) {
    if (error.status === 404) {
      return null
    }
    throw error
  }
}

// Discovery -----------------------------------------------------------------

export async function broadcastDiscovery(username, additionalInfo = '') {
  return request('/api/discovery/broadcast', {
    method: 'POST',
    json: { username, additionalInfo },
  })
}

export async function fetchDiscoveredPeers() {
  return request('/api/discovery/peers')
}

export async function fetchDiscoveredPeer(username) {
  return request(`/api/discovery/peers/${encodeURIComponent(username)}`)
}

// File transfer --------------------------------------------------------------

export async function sendFileTransfer({ peerIp, peerPort, filePath }) {
  return request('/api/filetransfer/send', {
    method: 'POST',
    json: { peerIp, peerPort, filePath },
  })
}

export async function getUserTransfers(username) {
  return request(`/api/filetransfer/transfers/${encodeURIComponent(username)}`)
}

export async function getTransferProgress(username, transferId) {
  return request(`/api/filetransfer/transfer/${encodeURIComponent(username)}/${encodeURIComponent(transferId)}`)
}

export async function getFileTransferStatus(username) {
  return request(`/api/filetransfer/status/${encodeURIComponent(username)}`)
}

export async function listDownloads() {
  return request('/api/filetransfer/downloads')
}

export function getDownloadUrl(filename) {
  return `${API_BASE}/api/filetransfer/download/${encodeURIComponent(filename)}`
}

// Whiteboard ----------------------------------------------------------------

export async function createWhiteboardSession(user1, user2) {
  return request('/api/whiteboard/create', {
    method: 'POST',
    json: { initiator: user1, participant: user2 },
  })
}

export async function sendWhiteboardDrawCommand(sessionId, username, type, x1, y1, x2, y2, color, thickness) {
  return request('/api/whiteboard/draw', {
    method: 'POST',
    json: { sessionId, username, type, x1, y1, x2, y2, color, thickness },
  })
}

export async function fetchWhiteboardCommands(sessionId, username) {
  return request(`/api/whiteboard/session/${encodeURIComponent(sessionId)}?username=${encodeURIComponent(username)}`)
}

export async function closeWhiteboardSession(sessionId, username) {
  return request('/api/whiteboard/close', {
    method: 'POST',
    json: { sessionId, username },
  })
}

export async function fetchPendingWhiteboardSessions(username) {
  return request(`/api/whiteboard/pending/${encodeURIComponent(username)}`)
}
