<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ currentStep: string; status: string }>()

const steps = [
  { code: 'topic_clarify', name: '主题澄清' },
  { code: 'academic_retrieve', name: '学术检索' },
  { code: 'outline_generate', name: '大纲生成' },
  { code: 'outline_quality_check', name: '大纲质检' },
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
