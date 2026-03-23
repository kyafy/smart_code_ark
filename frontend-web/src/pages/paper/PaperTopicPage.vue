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
const suggestionLoading = ref(false)
const adoptingIndex = ref<number | null>(null)

const degreeOptions = [
  { label: '本科', value: '本科' },
  { label: '硕士', value: '硕士' },
  { label: '博士', value: '博士' },
]

const canSubmit = computed(() => {
  return Boolean(paper.draft.topic.trim()) && Boolean(paper.draft.discipline.trim()) && Boolean(paper.draft.degreeLevel.trim())
})

const canSuggest = computed(() => {
  return Boolean(paper.draft.topic.trim()) || Boolean(paper.draft.discipline.trim())
})

const buildDirection = () => {
  const topic = paper.draft.topic.trim()
  const discipline = paper.draft.discipline.trim()
  if (topic && discipline) {
    return `${discipline}：${topic}`
  }
  return topic || discipline
}

const buildConstraints = () => {
  const parts: string[] = []
  if (paper.draft.degreeLevel.trim()) parts.push(`${paper.draft.degreeLevel.trim()}毕业论文`)
  if (paper.draft.methodPreference?.trim()) parts.push(`方法偏好：${paper.draft.methodPreference.trim()}`)
  return parts.join('；')
}

const onSuggest = async () => {
  if (!canSuggest.value) {
    ElMessage.warning('请至少填写研究方向或题目关键词')
    return
  }
  suggestionLoading.value = true
  try {
    const result = await paperApi.suggestTopics({
      sessionId: paper.suggestionSessionId ?? undefined,
      direction: buildDirection(),
      constraints: buildConstraints() || undefined,
    })
    paper.suggestionSessionId = result.sessionId
    paper.suggestionRound = result.round
    paper.suggestedTopics = Array.isArray(result.suggestions) ? result.suggestions : []
    if (paper.suggestedTopics.length === 0) {
      ElMessage.warning('本轮未生成建议，请重试')
      return
    }
    ElMessage.success(`已生成第 ${result.round} 轮选题建议`)
  } catch (e) {
    showApiError(e)
  } finally {
    suggestionLoading.value = false
  }
}

const onAdopt = async (index: number) => {
  if (!paper.suggestionSessionId) {
    ElMessage.warning('请先生成选题建议')
    return
  }
  adoptingIndex.value = index
  try {
    const adopted = await paperApi.adoptTopic({
      sessionId: paper.suggestionSessionId,
      selectedIndex: index,
    })
    paper.draft.topic = adopted.topic || paper.suggestedTopics[index]?.title || paper.draft.topic
    paper.draft.discipline = adopted.discipline || paper.draft.discipline
    paper.draft.degreeLevel = adopted.degreeLevel || paper.draft.degreeLevel
    paper.draft.methodPreference = adopted.methodPreference || paper.draft.methodPreference
    ElMessage.success('已采纳该建议，可继续生成论文大纲')
  } catch (e) {
    showApiError(e)
  } finally {
    adoptingIndex.value = null
  }
}

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
    <div class="mt-1 text-sm text-slate-500">支持先做选题建议，再采纳进入大纲生成。</div>

    <div class="mt-6 grid grid-cols-1 gap-4 md:grid-cols-2">
      <div class="md:col-span-2">
        <div class="mb-2 text-sm font-medium">论文题目 / 方向</div>
        <el-input v-model="paper.draft.topic" placeholder="例如：人工智能在教育中的应用" />
      </div>

      <div>
        <div class="mb-2 text-sm font-medium">学科/方向</div>
        <el-input v-model="paper.draft.discipline" placeholder="例如：计算机科学 / 教育技术" />
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

    <div class="mt-6 flex flex-wrap items-center gap-2">
      <el-button :loading="suggestionLoading" :disabled="!canSuggest" @click="onSuggest">
        {{ paper.suggestedTopics.length ? '再推荐一轮' : '智能推荐选题' }}
      </el-button>
      <el-button @click="router.push({ name: 'projects' })">返回</el-button>
      <el-button type="primary" :disabled="!canSubmit" :loading="isSubmitting" @click="onSubmit">开始生成</el-button>
    </div>

    <div v-if="paper.suggestedTopics.length" class="mt-6 space-y-3 border-t pt-4">
      <div class="flex items-center justify-between">
        <div class="text-sm font-semibold">第 {{ paper.suggestionRound }} 轮选题建议</div>
        <div class="text-xs text-slate-500">可采纳后再手动微调题目</div>
      </div>
      <div class="grid grid-cols-1 gap-3 lg:grid-cols-2">
        <div v-for="(item, idx) in paper.suggestedTopics" :key="`${paper.suggestionRound}-${idx}`" class="rounded-xl border bg-slate-50 p-4">
          <div class="text-sm font-semibold">{{ idx + 1 }}. {{ item.title }}</div>
          <div class="mt-2 text-xs text-slate-600">{{ item.rationale }}</div>
          <div v-if="item.researchQuestions?.length" class="mt-3">
            <div class="text-xs font-medium text-slate-700">研究问题</div>
            <ul class="mt-1 list-disc space-y-1 pl-5 text-xs text-slate-600">
              <li v-for="(q, qidx) in item.researchQuestions" :key="qidx">{{ q }}</li>
            </ul>
          </div>
          <div v-if="item.keywords?.length" class="mt-3 flex flex-wrap gap-1">
            <el-tag v-for="(k, kidx) in item.keywords" :key="kidx" size="small" type="info">{{ k }}</el-tag>
          </div>
          <div class="mt-4">
            <el-button type="primary" plain size="small" :loading="adoptingIndex === idx" @click="onAdopt(idx)">采纳该建议</el-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

