import { NextResponse } from 'next/server'

export async function GET() {
  return NextResponse.json({
    status: 'UP',
    service: '__PROJECT_NAME__',
    timestamp: new Date().toISOString()
  })
}
