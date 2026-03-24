import { prisma } from '@/lib/prisma'
import { NextResponse } from 'next/server'

export async function GET() {
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
