import './globals.css'
import type { Metadata } from 'next'

export const metadata: Metadata = {
  title: '__DISPLAY_NAME__',
  description: 'Next.js + MySQL starter template'
}

export default function RootLayout({
  children
}: Readonly<{
  children: React.ReactNode
}>) {
  return (
    <html lang="zh-CN">
      <body>{children}</body>
    </html>
  )
}
