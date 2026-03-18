<script setup lang="ts">
import { ref } from 'vue'
import { Paperclip, Send } from 'lucide-vue-next'

const props = defineProps<{ disabled?: boolean }>()
const emit = defineEmits<{ (e: 'send', value: string): void }>()

const input = ref('')

const onSend = () => {
  const text = input.value.trim()
  if (!text) return
  emit('send', text)
  input.value = ''
}
</script>

<template>
  <div class="rounded-2xl border bg-white px-4 py-3 shadow-sm dark:border-slate-900 dark:bg-slate-950">
    <div class="flex items-center gap-3">
      <button
        type="button"
        class="flex h-10 w-10 items-center justify-center rounded-full bg-blue-600 text-white shadow-sm hover:bg-blue-700 disabled:cursor-not-allowed disabled:bg-slate-300 dark:disabled:bg-slate-800"
        :disabled="props.disabled"
        aria-label="附件"
      >
        <Paperclip class="h-5 w-5" />
      </button>
      <input
        v-model="input"
        class="h-10 w-full bg-transparent text-sm outline-none placeholder:text-slate-400 dark:placeholder:text-slate-600"
        placeholder="输入消息..."
        :disabled="props.disabled"
        @keydown.enter.prevent="onSend"
      >
      <button
        type="button"
        class="flex h-10 w-10 items-center justify-center rounded-full bg-blue-600 text-white shadow-sm hover:bg-blue-700 disabled:cursor-not-allowed disabled:bg-slate-300 dark:disabled:bg-slate-800"
        :disabled="props.disabled"
        @click="onSend"
        aria-label="发送"
      >
        <Send class="h-5 w-5" />
      </button>
    </div>
  </div>
</template>
