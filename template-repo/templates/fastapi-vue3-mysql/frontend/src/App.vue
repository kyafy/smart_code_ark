<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { createUser, fetchHealth, fetchUsers, type HealthResponse, type User } from './api'

const health = ref<HealthResponse | null>(null)
const users = ref<User[]>([])
const loading = ref(false)
const submitting = ref(false)
const errorMessage = ref('')

const form = ref({
  name: '',
  email: ''
})

async function loadDashboard() {
  loading.value = true
  errorMessage.value = ''
  try {
    const [healthResult, userResult] = await Promise.all([fetchHealth(), fetchUsers()])
    health.value = healthResult
    users.value = userResult
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function handleSubmit() {
  submitting.value = true
  errorMessage.value = ''
  try {
    await createUser(form.value)
    form.value = { name: '', email: '' }
    await loadDashboard()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '提交失败'
  } finally {
    submitting.value = false
  }
}

onMounted(loadDashboard)
</script>

<template>
  <main class="page">
    <section class="hero">
      <p class="eyebrow">Template Repo v1</p>
      <h1>__DISPLAY_NAME__</h1>
      <p class="subtitle">FastAPI + Vue 3 + MySQL 可运行模板</p>
    </section>

    <section class="grid">
      <article class="panel">
        <h2>服务状态</h2>
        <p v-if="loading">正在连接后端...</p>
        <div v-else-if="health" class="status">
          <strong>{{ health.status }}</strong>
          <span>{{ health.service }}</span>
          <small>{{ health.databaseUrl }}</small>
        </div>
        <p v-if="errorMessage" class="error">{{ errorMessage }}</p>
      </article>

      <article class="panel">
        <h2>新增用户</h2>
        <form class="form" @submit.prevent="handleSubmit">
          <input v-model="form.name" type="text" placeholder="姓名" required maxlength="64" />
          <input v-model="form.email" type="email" placeholder="邮箱" required maxlength="128" />
          <button :disabled="submitting" type="submit">
            {{ submitting ? '提交中...' : '创建用户' }}
          </button>
        </form>
      </article>
    </section>

    <section class="panel">
      <div class="section-title">
        <h2>用户列表示例</h2>
        <button class="secondary" @click="loadDashboard">刷新</button>
      </div>

      <ul v-if="users.length" class="list">
        <li v-for="user in users" :key="user.id">
          <strong>{{ user.name }}</strong>
          <span>{{ user.email }}</span>
        </li>
      </ul>
      <p v-else class="empty">当前还没有用户数据</p>
    </section>
  </main>
</template>
