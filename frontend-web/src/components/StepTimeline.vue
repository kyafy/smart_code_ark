<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ currentStep: string; status: string }>()

const steps = [
  { code: 'requirement_analyze', name: '需求分析' },
  { code: 'codegen_backend', name: '生成后端' },
  { code: 'codegen_frontend', name: '生成前端' },
  { code: 'sql_generate', name: '生成 SQL' },
  { code: 'package', name: '打包交付物' },
]

const activeIndex = computed(() => {
  if (props.status === 'finished') return steps.length
  const idx = steps.findIndex((s) => s.code === props.currentStep)
  return idx >= 0 ? idx : 0
})
</script>

<template>
  <el-steps :active="activeIndex" align-center>
    <el-step v-for="s in steps" :key="s.code" :title="s.name" />
  </el-steps>
</template>

