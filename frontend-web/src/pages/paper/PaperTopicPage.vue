<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { paperApi } from '@/api/endpoints'
import { showApiError } from '@/api/http'
import { usePaperStore } from '@/stores/paper'
import { QUERY_FROM, QUERY_SOURCE_TASK_ID } from '@/constants/navigation'

const router = useRouter()
const paper = usePaperStore()

const isSubmitting = ref(false)

const degreeOptions = [
  { label: '本科', value: '本科' },
  { label: '硕士', value: '硕士' },
  { label: '博士', value: '博士' },
]

const canSubmit = computed(() => {
  return Boolean(paper.draft.topic.trim()) && Boolean(paper.draft.discipline.trim()) && Boolean(paper.draft.degreeLevel.trim())
})

const onSubmit = async () => {
  if (!canSubmit.value) {
    ElMessage.warning('请填写题目、学科与学历层次')
    return
  }

  isSubmitting.value = true
  try {
    const res = await paperApi.generateOutline({
      topic: paper.draft.topic.trim(),
      discipline: paper.draft.discipline.trim(),
      degreeLevel: paper.draft.degreeLevel.trim(),
      methodPreference: paper.draft.methodPreference?.trim() || undefined,
    })
    paper.taskId = res.taskId
    await router.replace({
      name: 'paper-outline-progress',
      params: { taskId: res.taskId },
      query: { [QUERY_FROM]: 'paper', [QUERY_SOURCE_TASK_ID]: res.taskId },
    })
  } catch (e) {
    showApiError(e)
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <div class="rounded-2xl border bg-white p-5">
    <div class="text-base font-semibold">论文框架生成</div>
    <div class="mt-1 text-sm text-slate-500">填写题目与研究信息后，将生成论文大纲与质量检查结果。</div>

    <div class="mt-6 grid grid-cols-1 gap-4 md:grid-cols-2">
      <div class="md:col-span-2">
        <div class="mb-2 text-sm font-medium">论文题目</div>
        <el-input v-model="paper.draft.topic" placeholder="例如：基于多智能体的代码生成系统设计与实现" />
      </div>

      <div>
        <div class="mb-2 text-sm font-medium">学科/方向</div>
        <el-input v-model="paper.draft.discipline" placeholder="例如：计算机科学 / 软件工程" />
      </div>

      <div>
        <div class="mb-2 text-sm font-medium">学历层次</div>
        <el-select v-model="paper.draft.degreeLevel" placeholder="请选择">
          <el-option v-for="o in degreeOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </div>

      <div class="md:col-span-2">
        <div class="mb-2 text-sm font-medium">研究方法偏好（可选）</div>
        <el-input v-model="paper.draft.methodPreference" placeholder="例如：实验法 / 调研法 / 对比分析" />
      </div>
    </div>

    <div class="mt-6 flex items-center gap-2">
      <el-button @click="router.push({ name: 'projects' })">返回</el-button>
      <el-button type="primary" :disabled="!canSubmit" :loading="isSubmitting" @click="onSubmit">开始生成</el-button>
    </div>
  </div>
</template>
