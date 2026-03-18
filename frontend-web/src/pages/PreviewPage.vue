<template>
  <div class="h-screen w-full flex flex-col bg-gray-100">
    <div class="h-14 bg-white border-b flex items-center px-4 justify-between shadow-sm z-10">
      <div class="flex items-center gap-2">
        <h1 class="font-semibold text-lg">Project Preview</h1>
        <span class="text-xs px-2 py-0.5 bg-green-100 text-green-700 rounded-full">Live</span>
      </div>
      <div class="text-sm text-gray-500">
        Task ID: {{ route.params.taskId }}
      </div>
    </div>
    
    <div class="flex-1 overflow-hidden relative">
      <div class="absolute inset-0 flex items-center justify-center">
        <div class="bg-white p-8 rounded-lg shadow-lg max-w-2xl w-full mx-4 text-center">
          <div class="w-16 h-16 bg-blue-100 text-blue-600 rounded-full flex items-center justify-center mx-auto mb-4">
            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="w-8 h-8">
              <path stroke-linecap="round" stroke-linejoin="round" d="M9 17.25v1.007a3 3 0 0 1-.879 2.122L7.5 21h9l-.621-.621A3 3 0 0 1 15 17.25V15m-3 0-3-3m0 0-3 3m3-3V15" />
            </svg>
          </div>
          <h2 class="text-2xl font-bold text-gray-900 mb-2">Welcome to Your Generated App</h2>
          <p class="text-gray-500 mb-6">
            This is a preview placeholder. The actual application code has been generated and is ready for download.
            In a production environment, this would host the live version of your deployed application.
          </p>
          
          <div class="grid grid-cols-1 md:grid-cols-3 gap-4 text-left">
            <div class="p-4 border rounded-lg bg-gray-50">
              <div class="font-medium text-gray-900 mb-1">Frontend</div>
              <div class="text-sm text-gray-500">Vue 3 + Tailwind</div>
            </div>
            <div class="p-4 border rounded-lg bg-gray-50">
              <div class="font-medium text-gray-900 mb-1">Backend</div>
              <div class="text-sm text-gray-500">Spring Boot 3</div>
            </div>
            <div class="p-4 border rounded-lg bg-gray-50">
              <div class="font-medium text-gray-900 mb-1">Database</div>
              <div class="text-sm text-gray-500">MySQL 8.0</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router'

const route = useRoute()
</script>
