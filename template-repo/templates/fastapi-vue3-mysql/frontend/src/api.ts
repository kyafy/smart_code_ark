export type HealthResponse = {
  status: string
  service: string
  databaseUrl: string
}

export type User = {
  id: number
  name: string
  email: string
  created_at: string
}

const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL?.replace(/\/$/, '') ?? 'http://localhost:8000'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    headers: {
      'Content-Type': 'application/json',
      ...(init?.headers ?? {})
    },
    ...init
  })

  if (!response.ok) {
    const message = await response.text()
    throw new Error(message || `Request failed with status ${response.status}`)
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
