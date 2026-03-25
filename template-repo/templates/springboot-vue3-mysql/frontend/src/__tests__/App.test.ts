import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import App from '../App.vue'
import * as api from '../api'

vi.mock('../api', () => ({
  fetchHealth: vi.fn(),
  fetchUsers: vi.fn(),
  createUser: vi.fn()
}))

describe('App.vue', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders the page title', () => {
    vi.mocked(api.fetchHealth).mockResolvedValue({ status: 'UP', service: 'test', timestamp: '2025-01-01' })
    vi.mocked(api.fetchUsers).mockResolvedValue([])

    const wrapper = mount(App)

    expect(wrapper.find('h1').exists()).toBe(true)
  })

  it('loads and displays users on mount', async () => {
    vi.mocked(api.fetchHealth).mockResolvedValue({ status: 'UP', service: 'test', timestamp: '2025-01-01' })
    vi.mocked(api.fetchUsers).mockResolvedValue([
      { id: 1, name: 'Alice', email: 'alice@test.com', createdAt: '2025-01-01' }
    ])

    const wrapper = mount(App)
    await flushPromises()

    expect(wrapper.text()).toContain('Alice')
    expect(wrapper.text()).toContain('alice@test.com')
  })

  it('shows empty state when no users', async () => {
    vi.mocked(api.fetchHealth).mockResolvedValue({ status: 'UP', service: 'test', timestamp: '2025-01-01' })
    vi.mocked(api.fetchUsers).mockResolvedValue([])

    const wrapper = mount(App)
    await flushPromises()

    expect(wrapper.text()).toContain('当前还没有用户数据')
  })

  it('submits new user form and refreshes list', async () => {
    vi.mocked(api.fetchHealth).mockResolvedValue({ status: 'UP', service: 'test', timestamp: '2025-01-01' })
    vi.mocked(api.fetchUsers).mockResolvedValue([])
    vi.mocked(api.createUser).mockResolvedValue({ id: 2, name: 'Bob', email: 'bob@test.com', createdAt: '2025-01-01' })

    const wrapper = mount(App)
    await flushPromises()

    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('Bob')
    await inputs[1].setValue('bob@test.com')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(api.createUser).toHaveBeenCalledWith({ name: 'Bob', email: 'bob@test.com' })
  })

  it('displays error message on fetch failure', async () => {
    vi.mocked(api.fetchHealth).mockRejectedValue(new Error('Network error'))
    vi.mocked(api.fetchUsers).mockRejectedValue(new Error('Network error'))

    const wrapper = mount(App)
    await flushPromises()

    expect(wrapper.text()).toContain('Network error')
  })
})
