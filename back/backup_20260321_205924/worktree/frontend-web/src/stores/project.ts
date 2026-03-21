import { defineStore } from 'pinia'
import { ref } from 'vue'
import { projectApi } from '@/api/endpoints'
import type { ProjectSummary } from '@/types/api'

export const useProjectStore = defineStore('project', () => {
  const projects = ref<ProjectSummary[]>([])
  const isLoading = ref(false)

  const refresh = async () => {
    isLoading.value = true
    try {
      projects.value = await projectApi.list()
    } finally {
      isLoading.value = false
    }
  }

  return {
    projects,
    isLoading,
    refresh,
  }
})

