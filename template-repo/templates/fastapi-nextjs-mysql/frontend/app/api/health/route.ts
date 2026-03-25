import { NextResponse } from "next/server";

const apiBaseUrl = process.env.BACKEND_API_BASE_URL ?? "http://backend:8000";

export async function GET() {
  const response = await fetch(`${apiBaseUrl}/api/health`, {
    cache: "no-store"
  });
  const payload = await response.json();
  return NextResponse.json(payload, { status: response.status });
}
