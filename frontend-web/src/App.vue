<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppLayout from '@/layouts/AppLayout.vue'

const route = useRoute()
const layout = computed(() => (route.meta.layout as string | undefined) ?? 'app')
</script>

<template>
  <AppLayout v-if="layout === 'app'">
    <router-view />
  </AppLayout>
  <router-view v-else />
</template>
