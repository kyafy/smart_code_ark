<script setup lang="ts">
import { Sparkles } from 'lucide-vue-next'
</script>

<template>
  <div class="flex items-center gap-3 text-sm text-slate-500 dark:text-slate-400">
    <div class="flex items-center gap-1.5">
      <Sparkles class="h-4 w-4 animate-pulse text-blue-500 dark:text-blue-400" />
      <span>AI 正在思考中</span>
    </div>
    <div class="flex gap-1">
      <span class="h-1.5 w-1.5 rounded-full bg-blue-500 dark:bg-blue-400 animate-bounce" style="animation-delay: 0ms"></span>
      <span class="h-1.5 w-1.5 rounded-full bg-blue-500 dark:bg-blue-400 animate-bounce" style="animation-delay: 150ms"></span>
      <span class="h-1.5 w-1.5 rounded-full bg-blue-500 dark:bg-blue-400 animate-bounce" style="animation-delay: 300ms"></span>
    </div>
  </div>
</template>