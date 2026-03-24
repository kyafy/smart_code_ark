export type HealthResponse = {
  status: string
  service: string
  timestamp: string
}

export type User = {
  id: number
  name: string
  email: string
  createdAt: string
}

const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL?.replace(/\/$/, '') ?? 'http://localhost:8080'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    headers: {
      'Content-Type': 'application/json',
      ...(init?.headers ?? {})
    },
    ...init
  })

  if (!response.ok) {
    const errorText = await response.text()
    throw new Error(errorText || `Request failed with status ${response.status}`)
  }

  return response.json() as Promise<T>
}

export function fetchHealth() {
  return request<HealthResponse>('/api/health')
}

export function fetchUsers() {
  return request<User[]>('/api/users')
}

export function createUser(payload: { name: string; email: string }) {
  return request<User>('/api/users', {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}
