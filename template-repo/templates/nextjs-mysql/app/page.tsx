import { CreateTodoForm } from '@/components/create-todo-form'
import { prisma } from '@/lib/prisma'

export default async function HomePage() {
  const todos = await prisma.todo.findMany({
    orderBy: {
      createdAt: 'desc'
    }
  })

  return (
    <main className="page">
      <section className="hero">
        <p className="eyebrow">Template Repo v1</p>
        <h1>__DISPLAY_NAME__</h1>
        <p>Next.js App Router + Prisma + MySQL 的第一版官方风格模板，适合快速起一个带数据库的 React 全栈项目。</p>
      </section>

      <section className="grid">
        <article className="panel">
          <h2>任务列表</h2>
          <div className="metric">共 {todos.length} 条任务</div>
          <ul className="todo-list">
            {todos.map((todo) => (
              <li className="todo-item" key={todo.id}>
                <div>
                  <strong>{todo.title}</strong>
                  <div className="muted">{todo.description || '暂无描述'}</div>
                </div>
                <span className="badge">{todo.status}</span>
              </li>
            ))}
            {todos.length === 0 ? <li className="muted">数据库已连接，但当前没有任务数据。</li> : null}
          </ul>
        </article>

        <article className="panel">
          <h2>新增任务</h2>
          <CreateTodoForm />
        </article>
      </section>
    </main>
  )
}
