'use client'

import { useRouter } from 'next/navigation'
import { FormEvent, useState } from 'react'

export function CreateTodoForm() {
  const router = useRouter()
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setLoading(true)
    setError('')

    const response = await fetch('/api/todos', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ title, description })
    })

    if (!response.ok) {
      const payload = (await response.json().catch(() => null)) as { message?: string } | null
      setError(payload?.message ?? '创建任务失败')
      setLoading(false)
      return
    }

    setTitle('')
    setDescription('')
    setLoading(false)
    router.refresh()
  }

  return (
    <form className="form" onSubmit={handleSubmit}>
      <input
        className="input"
        value={title}
        onChange={(event) => setTitle(event.target.value)}
        placeholder="任务标题"
        maxLength={100}
        required
      />
      <textarea
        className="textarea"
        value={description}
        onChange={(event) => setDescription(event.target.value)}
        placeholder="任务描述"
        maxLength={500}
      />
      <button className="button" type="submit" disabled={loading}>
        {loading ? '提交中...' : '新增任务'}
      </button>
      {error ? <div className="muted">{error}</div> : null}
    </form>
  )
}
