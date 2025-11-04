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

export { API_BASE }
