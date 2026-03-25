import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "__DISPLAY_NAME__",
  description: "FastAPI and Next.js starter template"
};

export default function RootLayout({
  children
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
