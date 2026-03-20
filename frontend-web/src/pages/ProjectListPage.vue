<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useProjectStore } from '@/stores/project'
import { LayoutDashboard } from 'lucide-vue-next'

const router = useRouter()
const projectStore = useProjectStore()

const allProjects = computed(() => projectStore.projects)

onMounted(() => {
  void projectStore.refresh()
})
</script>

<template>
  <div class="mx-auto max-w-5xl">
    <div class="flex items-center gap-3 mb-6">
      <div class="flex h-10 w-10 items-center justify-center rounded-xl bg-blue-50 text-blue-600 dark:bg-blue-950/40 dark:text-blue-300">
        <LayoutDashboard class="h-5 w-5" />
      </div>
      <div class="text-xl font-semibold">我的项目</div>
    </div>

    <div class="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
      <button
        v-for="p in allProjects"
        :key="p.id"
        type="button"
        class="rounded-2xl border bg-white p-5 text-left shadow-sm hover:shadow-md dark:border-slate-900 dark:bg-slate-950"
        @click="router.push({ name: 'project-detail', params: { projectId: p.id } })"
      >
        <div class="truncate text-base font-semibold">{{ p.title }}</div>
        <div class="mt-2 truncate text-sm text-slate-500 dark:text-slate-400">{{ p.description || '—' }}</div>
      </button>
      <div
        v-if="allProjects.length === 0"
        class="col-span-full rounded-2xl border bg-white p-10 text-center text-sm text-slate-500 dark:border-slate-900 dark:bg-slate-950 dark:text-slate-400"
      >
        暂无项目
      </div>
    </div>
  </div>
</template>
