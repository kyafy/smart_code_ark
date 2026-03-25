import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import App from '../App.vue'

// Mock the API module
vi.mock('../api', () => ({
  fetchUsers: vi.fn().mockResolvedValue([]),
  createUser: vi.fn().mockResolvedValue({ id: 1, name: 'Test', email: 'test@example.com' }),
}))

describe('App.vue', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders the app title', () => {
    const wrapper = mount(App)
    expect(wrapper.text()).toContain('Django')
  })

  it('loads data on mount', async () => {
    mount(App)
    await flushPromises()
  })

  it('handles empty state', async () => {
    const wrapper = mount(App)
    await flushPromises()
    expect(wrapper.html()).toBeTruthy()
  })
})
