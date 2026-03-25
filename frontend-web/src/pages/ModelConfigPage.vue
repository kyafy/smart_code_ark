<script setup lang="ts">
import ModelConfigTab from '@/components/project/ModelConfigTab.vue'
</script>

<template>
  <div class="h-[calc(100dvh-120px)] min-h-[640px] flex flex-col">
    <div class="mb-4">
      <h1 class="text-2xl font-semibold text-text-primary">模型配置</h1>
      <p class="text-sm text-text-tertiary mt-1">管理模型信息、密钥配置与连通性测试</p>
    </div>
    <div class="flex-1 min-h-0">
      <ModelConfigTab />
    </div>
  </div>
</template>
