import { prisma } from '@/lib/prisma'
import { NextResponse } from 'next/server'

export async function GET() {
  // Keep list handlers compact in the template so generated route code has a
  // clean example of query-then-return flow.
  const todos = await prisma.todo.findMany({
    orderBy: {
      createdAt: 'desc'
    }
  })

  return NextResponse.json(todos)
}

export async function POST(request: Request) {
  const payload = (await request.json()) as {
    title?: string
    description?: string
  }

  // Validate the key business rule before the database write so route behavior
  // stays obvious as more fields are added later.
  if (!payload.title?.trim()) {
    return NextResponse.json({ message: 'title is required' }, { status: 400 })
  }

  const todo = await prisma.todo.create({
    data: {
      title: payload.title.trim(),
      description: payload.description?.trim() || null
    }
  })

  return NextResponse.json(todo, { status: 201 })
}
