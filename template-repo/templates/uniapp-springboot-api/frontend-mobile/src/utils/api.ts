export type NewsItem = {
  id: number
  title: string
  summary: string
  actionText: string
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8081'

export function getHealth(): Promise<{
  status: string
  service: string
  timestamp: string
}> {
  return request('/api/health')
}

export function getNews() {
  return request<NewsItem[]>('/api/news')
}

function request<T>(path: string): Promise<T> {
  return new Promise((resolve, reject) => {
    uni.request({
      url: `${API_BASE_URL}${path}`,
      method: 'GET',
      success: (response) => {
        resolve(response.data as T)
      },
      fail: reject
    })
  })
}
