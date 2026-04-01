<script setup lang="ts">
import { computed } from 'vue'

type StepItem = { code: string; name: string }

const defaultSteps: StepItem[] = [
  { code: 'requirement_analyze', name: 'Requirement Analyze' },
  { code: 'codegen_backend', name: 'Backend Codegen' },
  { code: 'codegen_frontend', name: 'Frontend Codegen' },
  { code: 'sql_generate', name: 'SQL Generate' },
  { code: 'artifact_contract_validate', name: 'Contract Validate' },
  { code: 'build_verify', name: 'Build Verify' },
  { code: 'runtime_smoke_test', name: 'Runtime Smoke Test' },
  { code: 'package', name: 'Package Artifact' },
  { code: 'image_build', name: 'Image Build' },
  { code: 'image_push', name: 'Image Push' },
  { code: 'deploy_target', name: 'Deploy Target' },
  { code: 'deploy_verify', name: 'Deploy Verify' },
  { code: 'deploy_rollback', name: 'Deploy Rollback' },
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
