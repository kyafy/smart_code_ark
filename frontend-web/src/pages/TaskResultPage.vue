<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import ArtifactCards from '@/components/ArtifactCards.vue'
import { taskApi } from '@/api/endpoints'
import { showApiError } from '@/api/http'

const route = useRoute()
const router = useRouter()

const taskId = computed(() => String(route.params.taskId))
const previewUrl = ref<string>('')
const isLoading = ref(false)

const changeInstructions = ref('')
const isModifying = ref(false)

const load = async () => {
  if (!taskId.value) return
  isLoading.value = true
  try {
    const p = await taskApi.preview(taskId.value)
    previewUrl.value = p.previewUrl
  } catch (e) {
    showApiError(e)
  } finally {
    isLoading.value = false
  }
}

onMounted(() => {
  void load()
})

const onDownload = async () => {
  try {
    const blob = await taskApi.download(taskId.value)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `smartark_${taskId.value}.zip`
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    showApiError(e)
  }
}

const onModify = async () => {
  const text = changeInstructions.value.trim()
  if (!text) {
    ElMessage.warning('请输入修改指令')
    return
  }
  isModifying.value = true
  try {
    const res = await taskApi.modify(taskId.value, { changeInstructions: text })
    await router.push({ name: 'task-progress', params: { taskId: res.taskId } })
  } catch (e) {
    showApiError(e)
  } finally {
    isModifying.value = false
  }
}
</script>

<template>
  <div class="flex flex-col gap-4">
    <div class="rounded-2xl border bg-white p-5">
      <div class="flex items-start justify-between gap-4">
        <div>
          <div class="text-base font-semibold">交付结果</div>
          <div class="mt-1 text-sm text-slate-500">任务 ID：{{ taskId }}</div>
        </div>
        <div class="flex items-center gap-2">
          <el-button @click="router.push({ name: 'task-progress', params: { taskId } })">返回进度</el-button>
          <el-button type="primary" :loading="isLoading" @click="onDownload">下载 ZIP</el-button>
        </div>
      </div>
    </div>

    <div class="grid grid-cols-1 gap-4 lg:grid-cols-3">
      <div class="lg:col-span-2">
        <ArtifactCards :preview-url="previewUrl" />
      </div>

      <div class="lg:col-span-1">
        <div class="rounded-2xl border bg-white p-5">
          <div class="text-sm font-semibold">继续修改</div>
          <div class="mt-1 text-xs text-slate-500">用中文描述改动，例如：订单模块增加待发货状态</div>

          <el-input
            v-model="changeInstructions"
            class="mt-4"
            type="textarea"
            :autosize="{ minRows: 4, maxRows: 8 }"
            placeholder="请输入修改指令"
          />
          <el-button class="mt-3 w-full" type="primary" :loading="isModifying" @click="onModify">
            生成修改版
          </el-button>

          <div class="mt-4 rounded-xl bg-slate-50 p-3 text-xs text-slate-600">
            建议一次只提出一个明确修改点，便于模型准确应用。
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

