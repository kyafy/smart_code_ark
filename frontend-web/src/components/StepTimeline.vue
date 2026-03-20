<script setup lang="ts">
import { computed } from 'vue'

type StepItem = { code: string; name: string }

const defaultSteps: StepItem[] = [
  { code: 'requirement_analyze', name: '需求分析' },
  { code: 'codegen_backend', name: '生成后端' },
  { code: 'codegen_frontend', name: '生成前端' },
  { code: 'sql_generate', name: '生成 SQL' },
  { code: 'package', name: '打包交付物' },
]

const props = defineProps<{ currentStep: string; status: string; steps?: StepItem[] }>()

const steps = computed(() => (props.steps && props.steps.length > 0 ? props.steps : defaultSteps))

const activeIndex = computed(() => {
  if (props.status === 'finished') return steps.value.length
  const idx = steps.value.findIndex((s) => s.code === props.currentStep)
  return idx >= 0 ? idx : 0
})
</script>

<template>
  <el-steps :active="activeIndex" align-center>
    <el-step v-for="s in steps" :key="s.code" :title="s.name" />
  </el-steps>
</template>
