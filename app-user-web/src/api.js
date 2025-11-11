import axios from 'axios'

const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080'

const client = axios.create({
  baseURL: API_BASE,
})

async function request(path, { method = 'GET', json, headers } = {}) {
  try {
    const config = {
      url: path,
      method,
      headers,
    }
    if (json !== undefined) {
      config.data = json
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

export async function login({ user, pass, fileTcp, voiceUdp, ipOverride }) {
  const payload = {
    user,
    pass,
  }
  if (fileTcp !== undefined) payload.fileTcp = fileTcp
  if (voiceUdp !== undefined) payload.voiceUdp = voiceUdp
  if (ipOverride !== undefined && ipOverride !== null && ipOverride !== '') {
    payload.ipOverride = ipOverride
  }
  return request('/api/nio/login', { method: 'POST', json: payload })
}

export async function logout(user) {
  if (!user) return
  try {
    await request('/api/nio/logout', { method: 'POST', json: { user } })
  } catch (error) {
    if (error.status !== 404) {
      throw error
    }
  }
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

export async function sendChat({ user, text }) {
  return request('/api/nio/message', { method: 'POST', json: { user, text } })
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

// Add these functions to your existing app-user-web/src/api.js file

export async function broadcastDiscovery(username, additionalInfo = '') {
  return request('/api/discovery/broadcast', {
    method: 'POST',
    json: { username, additionalInfo }
  })
}

export async function fetchDiscoveredPeers() {
  return request('/api/discovery/peers')
}

export async function fetchDiscoveredPeer(username) {
  return request(`/api/discovery/peers/${encodeURIComponent(username)}`)
}

// File Transfer API functions (Member 3 - P2P File Transfer)
export async function sendFileTransfer({ peerIp, peerPort, filePath, senderUsername }) {
  return request('/api/filetransfer/send', {
    method: 'POST',
    json: { peerIp, peerPort, filePath, senderUsername }
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

export { API_BASE }
