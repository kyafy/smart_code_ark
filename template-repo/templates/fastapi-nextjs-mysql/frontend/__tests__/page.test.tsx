import { render, screen, waitFor } from '@testing-library/react'
import Page from '../app/page'

// Mock fetch globally
const mockFetch = vi.fn()
global.fetch = mockFetch

describe('Home Page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockFetch.mockResolvedValue({
      ok: true,
      json: async () => ([]),
    })
  })

  it('renders the page heading', () => {
    render(<Page />)
    expect(screen.getByRole('heading')).toBeTruthy()
  })

  it('loads users on mount', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ([
        { id: 1, name: 'Test User', email: 'test@example.com' }
      ]),
    })
    render(<Page />)
    await waitFor(() => {
      expect(mockFetch).toHaveBeenCalled()
    })
  })

  it('handles empty state', async () => {
    render(<Page />)
    await waitFor(() => {
      expect(screen.queryByRole('listitem')).toBeNull()
    })
  })

  it('handles fetch error gracefully', async () => {
    mockFetch.mockRejectedValueOnce(new Error('Network error'))
    render(<Page />)
    await waitFor(() => {
      expect(mockFetch).toHaveBeenCalled()
    })
  })
})
