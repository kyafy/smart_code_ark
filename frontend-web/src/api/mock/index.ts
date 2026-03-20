import type {
  ApiResponse,
  AuthLoginResult,
  ChatReplyResult,
  ChatStartResult,
  GenerateResult,
  ProjectConfirmResult,
  ProjectSummary,
  SmsSendResult,
  TaskPreviewResult,
  TaskStatusResult,
} from '@/types/api'

type Method = 'GET' | 'POST' | 'DELETE'

type MockRequest = {
  method: Method
  url: string
  body?: unknown
  params?: Record<string, unknown>
}

type User = {
  userId: string
  token: string
  username?: string
  phone?: string
  balance?: number
  quota?: number
}

type Session = {
  sessionId: string
  title: string
  projectType: string
  messages: { speaker: 'user' | 'assistant'; message: string }[]
  draftModules: string[]
  updatedAt: number
}

type Project = {
  id: string
  title: string
  description?: string
  status: string
  updatedAt: string
}

type Task = {
  taskId: string
  projectId: string
  status: 'queued' | 'running' | 'finished' | 'failed' | 'timeout'
  progress: number
  step: string
  logs: { level: 'info' | 'warn' | 'error'; content: string; ts: number }[]
  previewStatus?: 'provisioning' | 'ready' | 'failed' | 'expired'
  previewPollCount?: number
  previewExpireAt?: string | null
  previewLastError?: string | null
}

type RechargeOrder = {
  orderId: string
  userId: string
  amount: number
  quota: number
  status: 'pending' | 'paid'
  payChannel: string
  paymentNo?: string | null
  paidAt?: string | null
  createdAt: string
  updatedAt: string
}

type BillingRecord = {
  id: number
  userId: string
  changeAmount: number
  currency: string
  reason: string
  balanceAfter: number
  createdAt: string
}

const nowIso = () => new Date().toISOString()

const genId = (prefix: string) => `${prefix}_${Math.random().toString(36).slice(2, 10)}`

const getState = () => {
  const raw = localStorage.getItem('__smartark_mock_state__')
  if (!raw) {
    const init = {
      users: [] as User[],
      sessions: [] as Session[],
      projects: [] as Project[],
      tasks: [] as Task[],
      rechargeOrders: [] as RechargeOrder[],
      billingRecords: [] as BillingRecord[],
    }
    localStorage.setItem('__smartark_mock_state__', JSON.stringify(init))
    return init
  }
  return JSON.parse(raw) as {
    users: User[]
    sessions: Session[]
    projects: Project[]
    tasks: Task[]
    rechargeOrders: RechargeOrder[]
    billingRecords: BillingRecord[]
  }
}

const setState = (next: ReturnType<typeof getState>) => {
  localStorage.setItem('__smartark_mock_state__', JSON.stringify(next))
}

const ok = <T>(data: T): ApiResponse<T> => ({ code: 0, message: 'ok', data })

export const mockDownloadZip = (taskId: string) => {
  const content = `smart_code_ark mock zip for task ${taskId} generated at ${nowIso()}`
  return new Blob([content], { type: 'application/zip' })
}

const ensureUser = () => {
  const state = getState()
  if (state.users.length > 0) return state.users[0]
  const u: User = {
    userId: '1',
    token: `mock_jwt_${genId('t')}`,
    username: 'demo',
    phone: '13800000000',
    balance: 0,
    quota: 0,
  }
  state.users.unshift(u)
  setState(state)
  return u
}

const pickModules = (text: string) => {
  const modules = ['用户', '商品', '订单', '支付', '评论', '统计']
  const picked = modules.filter((m) => text.includes(m))
  if (picked.length > 0) return picked.slice(0, 5)
  return ['用户', '商品', '订单']
}

const getTask = (taskId: string) => {
  const state = getState()
  const task = state.tasks.find((t) => t.taskId === taskId)
  if (!task) return null
  return { state, task }
}

const advanceTask = (task: Task) => {
  if (task.status === 'failed' || task.status === 'timeout' || task.status === 'finished') return
  if (task.status === 'queued') {
    task.status = 'running'
    task.step = 'requirement_analyze'
    task.logs.push({ level: 'info', content: '任务开始执行', ts: Date.now() })
    return
  }

  const steps = ['requirement_analyze', 'codegen_backend', 'codegen_frontend', 'sql_generate', 'package']
  const nextProgress = Math.min(100, task.progress + 15 + Math.floor(Math.random() * 10))
  task.progress = nextProgress
  const stepIndex = Math.min(steps.length - 1, Math.floor((task.progress / 100) * steps.length))
  task.step = steps[stepIndex]
  task.logs.push({ level: 'info', content: `执行中：${task.step}（${task.progress}%）`, ts: Date.now() })

  if (task.progress >= 100) {
    task.status = 'finished'
    task.step = 'finished'
    task.previewStatus = 'provisioning'
    task.previewPollCount = 0
    task.previewExpireAt = null
    task.previewLastError = null
    task.logs.push({ level: 'info', content: '任务完成', ts: Date.now() })
  }
}

export const handleMock = async (req: MockRequest): Promise<ApiResponse<unknown> | Blob> => {
  const u = ensureUser()
  const { method, url } = req
  const state = getState()
  if (u.balance == null) u.balance = 0
  if (u.quota == null) u.quota = 0

  if (method === 'POST' && url === '/api/auth/register') {
    return ok({ userId: u.userId })
  }

  if (method === 'POST' && url === '/api/auth/login') {
    const r: AuthLoginResult = { token: u.token, userId: u.userId }
    return ok(r)
  }

  if (method === 'POST' && url === '/api/auth/sms/send') {
    const r: SmsSendResult = { requestId: genId('sms'), expireIn: 300 }
    return ok(r)
  }

  if (method === 'POST' && url === '/api/auth/login/sms') {
    const r: AuthLoginResult = { token: u.token, userId: u.userId }
    return ok(r)
  }

  if (method === 'POST' && url === '/api/chat/start') {
    const body = (req.body ?? {}) as Record<string, unknown>
    const title = String(body.title ?? '未命名项目')
    const projectType = String(body.projectType ?? 'web')
    const sessionId = genId('s')
    const session: Session = {
      sessionId,
      title,
      projectType,
      messages: [],
      draftModules: [],
      updatedAt: Date.now(),
    }
    state.sessions.unshift(session)
    setState(state)
    const r: ChatStartResult = { sessionId, stage: 'requirement' }
    return ok(r)
  }

  if (method === 'POST' && url === '/api/chat') {
    const body = (req.body ?? {}) as Record<string, unknown>
    const sessionId = String(body.sessionId ?? '')
    const message = String(body.message ?? '')
    const session = state.sessions.find((s) => s.sessionId === sessionId)
    if (!session) return { code: 1004, message: 'session not found', data: null }
    session.messages.push({ speaker: 'user', message })
    const draft = pickModules(message)
    session.draftModules = Array.from(new Set([...session.draftModules, ...draft]))
    const reply = `已收到：${message}\n建议模块：${session.draftModules.join('、')}。你还需要哪些角色/权限？`
    session.messages.push({ speaker: 'assistant', message: reply })
    session.updatedAt = Date.now()
    setState(state)
    const r: ChatReplyResult = {
      sessionId,
      messages: session.messages.map((m) => ({
        role: m.speaker,
        content: m.message,
      })),
    }
    return ok(r)
  }

  if (method === 'DELETE' && url.startsWith('/api/chat/sessions/')) {
    const sessionId = url.replace('/api/chat/sessions/', '')
    const idx = state.sessions.findIndex((s) => s.sessionId === sessionId)
    if (idx === -1) {
      return ok(true)
    }
    state.sessions.splice(idx, 1)
    setState(state)
    return ok(true)
  }

  if (method === 'POST' && url === '/api/projects/confirm') {
    const body = (req.body ?? {}) as Record<string, unknown>
    const sessionId = String(body.sessionId ?? '')
    const session = state.sessions.find((s) => s.sessionId === sessionId)
    if (!session) return { code: 1004, message: 'session not found', data: null }
    const projectId = genId('p')
    const project: Project = {
      id: projectId,
      title: session.title,
      description: String((body as any).description ?? ''),
      status: 'draft',
      updatedAt: nowIso(),
    }
    state.projects.unshift(project)
    setState(state)
    const r: ProjectConfirmResult = { projectId, status: 'draft' }
    return ok(r)
  }

  if (method === 'GET' && url === '/api/projects') {
    const list: ProjectSummary[] = state.projects.map((p) => ({
      id: p.id,
      title: p.title,
      description: p.description,
      status: p.status,
      updatedAt: p.updatedAt,
    }))
    return ok(list)
  }

  if (method === 'GET' && url === '/api/user/profile') {
    return ok({
      userId: Number(u.userId),
      username: u.username || 'demo',
      balance: Number(u.balance ?? 0),
      quota: Number(u.quota ?? 0),
      createdAt: nowIso(),
    })
  }

  if (method === 'GET' && url === '/api/billing/balance') {
    return ok({
      balance: Number(u.balance ?? 0),
      quota: Number(u.quota ?? 0),
    })
  }

  if (method === 'GET' && url === '/api/billing/records') {
    const records = state.billingRecords
      .filter((r) => r.userId === u.userId)
      .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
    return ok(records)
  }

  if (method === 'POST' && url === '/api/billing/recharge/orders') {
    const body = (req.body ?? {}) as Record<string, unknown>
    const amount = Number(body.amount ?? 0)
    const quota = Number(body.quota ?? 0)
    const payChannel = String(body.payChannel ?? 'mock')
    const now = nowIso()
    const order: RechargeOrder = {
      orderId: genId('order'),
      userId: u.userId,
      amount,
      quota,
      status: 'pending',
      payChannel,
      paymentNo: null,
      paidAt: null,
      createdAt: now,
      updatedAt: now,
    }
    state.rechargeOrders.unshift(order)
    setState(state)
    return ok(order)
  }

  if (method === 'GET' && url.startsWith('/api/billing/recharge/orders/')) {
    const orderId = url.replace('/api/billing/recharge/orders/', '')
    const order = state.rechargeOrders.find((o) => o.orderId === orderId)
    if (!order || order.userId !== u.userId) {
      return { code: 1004, message: '订单不存在', data: null }
    }
    return ok(order)
  }

  if (method === 'POST' && url === '/api/billing/recharge/callback') {
    const body = (req.body ?? {}) as Record<string, unknown>
    const orderId = String(body.orderId ?? '')
    const paymentNo = String(body.paymentNo ?? '')
    const signature = String(body.signature ?? '')
    const order = state.rechargeOrders.find((o) => o.orderId === orderId)
    if (!order) {
      return { code: 1004, message: '订单不存在', data: null }
    }
    const expectedSign = `${orderId}|${paymentNo}|smartark-recharge-secret`
    if (signature !== expectedSign) {
      return { code: 1003, message: '验签失败', data: null }
    }
    if (order.status === 'paid') {
      return ok(order)
    }
    const duplicated = state.rechargeOrders.find((o) => o.paymentNo === paymentNo && o.status === 'paid')
    if (duplicated && duplicated.orderId !== orderId) {
      return { code: 1003, message: '支付单号已处理', data: null }
    }
    order.status = 'paid'
    order.paymentNo = paymentNo
    order.paidAt = nowIso()
    order.updatedAt = nowIso()
    u.quota = Number(u.quota ?? 0) + order.quota
    const record: BillingRecord = {
      id: Date.now(),
      userId: u.userId,
      changeAmount: order.quota,
      currency: 'QUOTA',
      reason: 'recharge',
      balanceAfter: Number(u.quota),
      createdAt: nowIso(),
    }
    state.billingRecords.unshift(record)
    setState(state)
    return ok(order)
  }

  if (method === 'POST' && url === '/api/generate') {
    const body = (req.body ?? {}) as Record<string, unknown>
    const projectId = String(body.projectId ?? '')
    const taskId = genId('t')
    const task: Task = {
      taskId,
      projectId,
      status: 'queued',
      progress: 0,
      step: 'queued',
      logs: [{ level: 'info', content: '任务已入队', ts: Date.now() }],
      previewStatus: 'provisioning',
      previewPollCount: 0,
      previewExpireAt: null,
      previewLastError: null,
    }
    state.tasks.unshift(task)
    setState(state)
    const r: GenerateResult = { taskId, status: 'queued' }
    return ok(r)
  }

  if (method === 'GET' && url.startsWith('/api/task/') && url.endsWith('/status')) {
    const taskId = url.replace('/api/task/', '').replace('/status', '')
    const data = getTask(taskId)
    if (!data) return { code: 1004, message: 'task not found', data: null }
    advanceTask(data.task)
    setState(data.state)
    const r: TaskStatusResult = {
      status: data.task.status,
      progress: data.task.progress,
      step: data.task.step,
      current_step: data.task.step,
    }
    return ok(r)
  }

  if (method === 'GET' && url.startsWith('/api/task/') && url.endsWith('/preview')) {
    const taskId = url.replace('/api/task/', '').replace('/preview', '')
    const data = getTask(taskId)
    if (!data) return { code: 1004, message: 'task not found', data: null }
    const task = data.task
    if (task.status !== 'finished') {
      const r: TaskPreviewResult = {
        taskId,
        status: 'provisioning',
        previewUrl: null,
        expireAt: null,
        lastError: null
      }
      setState(data.state)
      return ok(r)
    }
    task.previewStatus = task.previewStatus || 'provisioning'
    task.previewPollCount = task.previewPollCount ?? 0
    if (task.previewStatus === 'provisioning') {
      task.previewPollCount += 1
      if (task.previewPollCount >= 2) {
        task.previewStatus = 'ready'
        task.previewExpireAt = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString()
      }
    }
    const r: TaskPreviewResult = {
      taskId,
      status: task.previewStatus || 'provisioning',
      previewUrl: task.previewStatus === 'ready' ? `${window.location.origin}/preview/${taskId}` : null,
      expireAt: task.previewExpireAt ?? null,
      lastError: task.previewLastError ?? null
    }
    setState(data.state)
    return ok(r)
  }

  if (method === 'POST' && url.startsWith('/api/task/') && url.endsWith('/preview/rebuild')) {
    const taskId = url.replace('/api/task/', '').replace('/preview/rebuild', '')
    const data = getTask(taskId)
    if (!data) return { code: 1004, message: 'task not found', data: null }
    const task = data.task
    const activePreviewCount = data.state.tasks.filter((t) => {
      if (t.taskId === taskId) return false
      const status = t.previewStatus || 'provisioning'
      return status === 'provisioning' || status === 'ready'
    }).length
    if (activePreviewCount >= 2) {
      return { code: 3106, message: '预览并发数已达上限(2)', data: null }
    }
    const currentStatus = task.previewStatus || 'provisioning'
    if (currentStatus !== 'failed' && currentStatus !== 'expired') {
      return { code: 1005, message: 'only failed/expired preview can rebuild', data: null }
    }
    task.previewStatus = 'provisioning'
    task.previewPollCount = 0
    task.previewExpireAt = null
    task.previewLastError = null
    task.logs.push({ level: 'info', content: '预览重建已触发', ts: Date.now() })
    const r: TaskPreviewResult = {
      taskId,
      status: 'provisioning',
      previewUrl: null,
      expireAt: null,
      lastError: null
    }
    setState(data.state)
    return ok(r)
  }

  if (method === 'GET' && url.startsWith('/api/task/') && url.endsWith('/download')) {
    const taskId = url.replace('/api/task/', '').replace('/download', '')
    return mockDownloadZip(taskId)
  }

  if (method === 'POST' && url.startsWith('/api/task/') && url.endsWith('/modify')) {
    const taskId = url.replace('/api/task/', '').replace('/modify', '')
    const src = getTask(taskId)
    if (!src) return { code: 1004, message: 'task not found', data: null }
    const newTaskId = genId('t')
    const task: Task = {
      taskId: newTaskId,
      projectId: src.task.projectId,
      status: 'queued',
      progress: 0,
      step: 'queued',
      logs: [{ level: 'info', content: '修改任务已入队', ts: Date.now() }],
      previewStatus: 'provisioning',
      previewPollCount: 0,
      previewExpireAt: null,
      previewLastError: null,
    }
    state.tasks.unshift(task)
    setState(state)
    return ok({ taskId: newTaskId, status: 'queued' })
  }

  return { code: 1004, message: `mock route not found: ${method} ${url}`, data: null }
}
