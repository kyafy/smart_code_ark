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
    errorMessage.value = error instanceof Error ? error.message : 'Failed to load data'
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
    errorMessage.value = error instanceof Error ? error.message : 'Failed to submit'
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
      <p class="subtitle">Django + Vue 3 + MySQL starter template</p>
    </section>

    <section class="grid">
      <article class="panel">
        <h2>Service Status</h2>
        <p v-if="loading">Connecting to backend...</p>
        <div v-else-if="health" class="status">
          <strong>{{ health.status }}</strong>
          <span>{{ health.service }}</span>
        </div>
        <p v-if="errorMessage" class="error">{{ errorMessage }}</p>
      </article>

      <article class="panel">
        <h2>Create User</h2>
        <form class="form" @submit.prevent="handleSubmit">
          <input v-model="form.name" type="text" placeholder="Name" required maxlength="64" />
          <input v-model="form.email" type="email" placeholder="Email" required maxlength="128" />
          <button :disabled="submitting" type="submit">
            {{ submitting ? 'Submitting...' : 'Create user' }}
          </button>
        </form>
      </article>
    </section>

    <section class="panel">
      <div class="section-title">
        <h2>User List</h2>
        <button class="secondary" @click="loadDashboard">Refresh</button>
      </div>

      <ul v-if="users.length" class="list">
        <li v-for="user in users" :key="user.id">
          <strong>{{ user.name }}</strong>
          <span>{{ user.email }}</span>
        </li>
      </ul>
      <p v-else class="empty">No user records yet.</p>
    </section>
  </main>
</template>
