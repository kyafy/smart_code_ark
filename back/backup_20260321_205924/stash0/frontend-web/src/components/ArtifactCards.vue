<script setup lang="ts">
const props = defineProps<{ previewUrl?: string }>()

const artifacts = [
  { key: 'zip', title: '项目代码包（ZIP）', desc: '包含前后端源码、配置与示例' },
  { key: 'sql', title: '数据库脚本（SQL）', desc: '建表与初始化数据（如有）' },
  { key: 'openapi', title: '接口文档（OpenAPI）', desc: '用于前后端联调与接口查看' },
  { key: 'readme', title: 'README', desc: '启动说明与目录结构说明' },
  { key: 'deploy', title: '部署脚本', desc: 'Docker/compose 或部署指引（如有）' },
]
</script>

<template>
  <div class="grid grid-cols-1 gap-3">
    <div v-for="a in artifacts" :key="a.key" class="rounded-2xl border bg-white p-4">
      <div class="flex items-start justify-between gap-3">
        <div>
          <div class="text-sm font-semibold">{{ a.title }}</div>
          <div class="mt-1 text-xs text-slate-500">{{ a.desc }}</div>
        </div>
        <el-tag type="info" effect="plain">MVP</el-tag>
      </div>
    </div>

    <div v-if="props.previewUrl" class="rounded-2xl border bg-white p-4">
      <div class="text-sm font-semibold">在线预览</div>
      <a class="mt-2 block break-all text-sm text-blue-600 hover:underline" :href="props.previewUrl" target="_blank">
        {{ props.previewUrl }}
      </a>
    </div>
  </div>
</template>

