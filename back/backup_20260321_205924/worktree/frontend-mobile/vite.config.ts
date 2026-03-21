import { defineConfig } from 'vite'
import uniPlugin from '@dcloudio/vite-plugin-uni'
import path from 'path'

const uni = (uniPlugin as any).default ?? uniPlugin

export default defineConfig({
  plugins: [uni()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
      '@smartark/domain': path.resolve(__dirname, '../packages/domain/src'),
      '@smartark/api-sdk': path.resolve(__dirname, '../packages/api-sdk/src/index.ts'),
      '@smartark/constants': path.resolve(__dirname, '../packages/constants/src/index.ts'),
    },
  },
})
