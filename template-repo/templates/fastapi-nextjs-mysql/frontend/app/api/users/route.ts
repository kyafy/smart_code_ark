import { NextRequest, NextResponse } from "next/server";

const apiBaseUrl = process.env.BACKEND_API_BASE_URL ?? "http://backend:8000";

export async function GET() {
  const response = await fetch(`${apiBaseUrl}/api/users`, {
    cache: "no-store"
  });
  const payload = await response.json();
  return NextResponse.json(payload, { status: response.status });
}

export async function POST(request: NextRequest) {
  const response = await fetch(`${apiBaseUrl}/api/users`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: await request.text(),
    cache: "no-store"
  });
  const payload = await response.json();
  return NextResponse.json(payload, { status: response.status });
}
