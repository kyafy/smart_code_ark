import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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

  it('loads todos on mount', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ([
        { id: 1, title: 'Test Todo', completed: false, createdAt: new Date().toISOString() }
      ]),
    })
    render(<Page />)
    await waitFor(() => {
      expect(mockFetch).toHaveBeenCalled()
    })
  })

  it('handles empty todo list', async () => {
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
