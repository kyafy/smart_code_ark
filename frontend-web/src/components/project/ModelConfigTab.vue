<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { modelAdminApi } from '@/api/endpoints'
import { ApiRequestError, showApiError } from '@/api/http'
import type { ModelConnectivityTestResult, ModelRegistry, ModelUpsertPayload } from '@smartark/domain/api'

type ModelRole = 'chat' | 'code' | 'embedding'

const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const toggling = ref<string | null>(null)
const deleting = ref<string | null>(null)
const hasForbidden = ref(false)
const formRef = ref<FormInstance>()
const dialogVisible = ref(false)
const selectedModelName = ref<string | null>(null)
const models = ref<ModelRegistry[]>([])
const testResult = ref<ModelConnectivityTestResult | null>(null)
const lastTestSnapshot = ref<string | null>(null)

const form = reactive({
  modelName: '',
  displayName: '',
  provider: 'dashscope',
  modelRole: 'code' as ModelRole,
  dailyTokenLimit: 0,
  priority: 100,
  enabled: true,
  baseUrl: '',
  apiKey: ''
})

const testForm = reactive({
  prompt: '请回复OK',
  timeoutMs: 45000
})

const formRules: FormRules = {
  modelName: [{ required: true, message: '请输入模型名称', trigger: 'blur' }],
  dailyTokenLimit: [{ type: 'number', message: '请输入数字', trigger: 'change' }],
  priority: [{ type: 'number', message: '请输入数字', trigger: 'change' }]
}

const isEditMode = computed(() => !!selectedModelName.value)
const canSave = computed(() => !hasForbidden.value && !!lastTestSnapshot.value && lastTestSnapshot.value === buildSnapshot())

const roleOptions: Array<{ label: string; value: ModelRole }> = [
  { label: 'Chat', value: 'chat' },
  { label: 'Code', value: 'code' },
  { label: 'Embedding', value: 'embedding' }
]

const buildConnectionPayload = () => ({
  modelName: form.modelName.trim(),
  provider: form.provider.trim() || 'dashscope',
  baseUrl: form.baseUrl.trim() || undefined,
  apiKey: form.apiKey.trim() || undefined
})

const buildSavePayload = (): ModelUpsertPayload & { baseUrl?: string; apiKey?: string } => {
  const conn = buildConnectionPayload()
  return {
    modelName: conn.modelName,
    displayName: form.displayName.trim() || conn.modelName,
    provider: conn.provider,
    modelRole: form.modelRole,
    dailyTokenLimit: Number(form.dailyTokenLimit || 0),
    priority: Number(form.priority || 100),
    enabled: !!form.enabled,
    baseUrl: conn.baseUrl,
    apiKey: conn.apiKey
  }
}

const buildSnapshot = () => {
  const conn = buildConnectionPayload()
  return JSON.stringify({
    modelName: conn.modelName,
    provider: conn.provider,
    baseUrl: conn.baseUrl,
    apiKey: conn.apiKey
  })
}

const markConfigDirty = () => {
  lastTestSnapshot.value = null
  testResult.value = null
}

const applyModelToForm = (model: ModelRegistry) => {
  selectedModelName.value = model.modelName
  form.modelName = model.modelName
  form.displayName = model.displayName || model.modelName
  form.provider = model.provider || 'dashscope'
  form.modelRole = (model.modelRole as ModelRole) || 'code'
  form.dailyTokenLimit = Number(model.dailyTokenLimit ?? 0)
  form.priority = Number(model.priority ?? 100)
  form.enabled = !!model.enabled
  form.baseUrl = ''
  form.apiKey = ''
  markConfigDirty()
}

const resetToCreateMode = () => {
  selectedModelName.value = null
  form.modelName = ''
  form.displayName = ''
  form.provider = 'dashscope'
  form.modelRole = 'code'
  form.dailyTokenLimit = 0
  form.priority = 100
  form.enabled = true
  form.baseUrl = ''
  form.apiKey = ''
  testForm.prompt = '请回复OK'
  testForm.timeoutMs = 45000
  markConfigDirty()
  formRef.value?.clearValidate()
}

const resetCurrentForm = () => {
  if (!selectedModelName.value) {
    resetToCreateMode()
    return
  }
  const current = models.value.find((m) => m.modelName === selectedModelName.value)
  if (!current) {
    resetToCreateMode()
    return
  }
  applyModelToForm(current)
}

const loadModels = async () => {
  loading.value = true
  hasForbidden.value = false
  try {
    models.value = await modelAdminApi.list()
    if (selectedModelName.value) {
      const selected = models.value.find((m) => m.modelName === selectedModelName.value)
      if (selected) {
        applyModelToForm(selected)
      }
    }
  } catch (e) {
    if (e instanceof ApiRequestError && (e.code === 1003 || e.httpStatus === 403)) {
      hasForbidden.value = true
      return
    }
    showApiError(e)
  } finally {
    loading.value = false
  }
}

const onSubmitSave = async () => {
  if (hasForbidden.value) {
    return
  }
  if (!canSave.value) {
    ElMessage.warning('请先完成并通过模型连通性测试后再保存')
    return
  }
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  const payload = buildSavePayload()
  saving.value = true
  try {
    await modelAdminApi.upsert(payload)
    ElMessage.success('模型配置已保存')
    await loadModels()
    selectedModelName.value = payload.modelName
    lastTestSnapshot.value = buildSnapshot()
  } catch (e) {
    showApiError(e)
  } finally {
    saving.value = false
  }
}

const onToggle = async (row: ModelRegistry) => {
  toggling.value = row.modelName
  try {
    await modelAdminApi.toggle(row.modelName)
    ElMessage.success(row.enabled ? '模型已停用' : '模型已启用')
    await loadModels()
  } catch (e) {
    showApiError(e)
  } finally {
    toggling.value = null
  }
}

const onDelete = async (row: ModelRegistry) => {
  try {
    await ElMessageBox.confirm(`将删除模型 ${row.modelName}，是否继续？`, '确认删除', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  deleting.value = row.modelName
  try {
    await modelAdminApi.remove(row.modelName)
    ElMessage.success('模型已删除')
    if (selectedModelName.value === row.modelName) {
      resetToCreateMode()
    }
    await loadModels()
  } catch (e) {
    showApiError(e)
  } finally {
    deleting.value = null
  }
}

const openTestDialog = async () => {
  const valid = await formRef.value?.validateField(['modelName']).then(() => true).catch(() => false)
  if (!valid) {
    return
  }
  dialogVisible.value = true
  testResult.value = null
}

const onRunConnectivityTest = async () => {
  testing.value = true
  try {
    const conn = buildConnectionPayload()
    const result = await modelAdminApi.testConnectivityMvp({
      modelName: conn.modelName,
      provider: conn.provider,
      prompt: testForm.prompt.trim() || '请回复OK',
      timeoutMs: Number(testForm.timeoutMs || 45000),
      baseUrl: conn.baseUrl,
      apiKey: conn.apiKey
    })
    testResult.value = result
    if (result.ok) {
      lastTestSnapshot.value = buildSnapshot()
      ElMessage.success('模型连通性测试通过')
    } else {
      lastTestSnapshot.value = null
      ElMessage.error(result.errorMessage || '模型连通性测试失败')
    }
  } catch (e) {
    lastTestSnapshot.value = null
    testResult.value = null
    showApiError(e)
  } finally {
    testing.value = false
  }
}

onMounted(() => {
  resetToCreateMode()
  loadModels()
})
</script>

<template>
  <div class="h-full overflow-y-auto pb-6 space-y-4">
    <el-alert
      v-if="hasForbidden"
      type="warning"
      :closable="false"
      title="无权限访问模型管理接口"
      description="当前账号不具备模型管理权限，请联系管理员开通。"
      show-icon
    />

    <template v-else>
      <div class="bg-surface rounded-xl border border-border p-6">
        <div class="flex items-center justify-between mb-4">
          <div>
            <div class="text-lg font-semibold text-text-primary">模型信息配置</div>
            <div class="text-xs text-text-tertiary mt-1">先测试连通性，再保存配置</div>
          </div>
          <el-tag v-if="isEditMode" type="info">当前编辑：{{ selectedModelName }}</el-tag>
        </div>

        <el-form ref="formRef" :model="form" :rules="formRules" label-width="110px">
          <div class="grid grid-cols-1 md:grid-cols-2 gap-x-6">
            <el-form-item label="模型名称" prop="modelName">
              <el-input v-model="form.modelName" :disabled="isEditMode" @input="markConfigDirty" />
            </el-form-item>
            <el-form-item label="显示名称">
              <el-input v-model="form.displayName" @input="markConfigDirty" />
            </el-form-item>
            <el-form-item label="提供方">
              <el-input v-model="form.provider" @input="markConfigDirty" />
            </el-form-item>
            <el-form-item label="模型角色">
              <el-select v-model="form.modelRole" class="w-full" @change="markConfigDirty">
                <el-option v-for="opt in roleOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="每日配额" prop="dailyTokenLimit">
              <el-input-number v-model="form.dailyTokenLimit" :min="0" class="w-full" @change="markConfigDirty" />
            </el-form-item>
            <el-form-item label="优先级" prop="priority">
              <el-input-number v-model="form.priority" :min="0" class="w-full" @change="markConfigDirty" />
            </el-form-item>
            <el-form-item label="启用状态">
              <el-switch v-model="form.enabled" @change="markConfigDirty" />
            </el-form-item>
            <el-form-item label="Base URL">
              <el-input v-model="form.baseUrl" placeholder="可选：显式测试使用" @input="markConfigDirty" />
            </el-form-item>
            <el-form-item label="API Key">
              <el-input v-model="form.apiKey" type="password" show-password placeholder="可选：显式测试使用" @input="markConfigDirty" />
            </el-form-item>
          </div>

          <div class="flex flex-wrap gap-2 mt-2">
            <el-button type="primary" :loading="saving" :disabled="!canSave" @click="onSubmitSave">保存</el-button>
            <el-button type="success" plain :disabled="!form.modelName.trim()" @click="openTestDialog">测试连通性</el-button>
            <el-button @click="resetToCreateMode">新增/清空</el-button>
            <el-button @click="resetCurrentForm">重置</el-button>
            <el-tag v-if="lastTestSnapshot" type="success">已通过测试</el-tag>
            <el-tag v-else type="warning">待测试</el-tag>
          </div>
        </el-form>
      </div>

      <div class="bg-surface rounded-xl border border-border p-6">
        <div class="flex items-center justify-between mb-4">
          <div class="text-lg font-semibold text-text-primary">模型列表</div>
          <el-button :loading="loading" @click="loadModels">刷新</el-button>
        </div>

        <el-table :data="models" v-loading="loading" row-key="modelName" @row-click="applyModelToForm">
          <el-table-column prop="modelName" label="模型名称" min-width="160" />
          <el-table-column prop="displayName" label="显示名称" min-width="140" />
          <el-table-column prop="provider" label="Provider" min-width="120" />
          <el-table-column prop="modelRole" label="角色" min-width="100" />
          <el-table-column prop="dailyTokenLimit" label="每日配额" min-width="110" />
          <el-table-column prop="priority" label="优先级" min-width="90" />
          <el-table-column label="状态" min-width="100">
            <template #default="{ row }">
              <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="updatedAt" label="更新时间" min-width="180" />
          <el-table-column label="操作" min-width="240" fixed="right">
            <template #default="{ row }">
              <div class="flex items-center gap-2">
                <el-button link type="primary" @click.stop="applyModelToForm(row)">编辑</el-button>
                <el-button link type="primary" :loading="toggling === row.modelName" @click.stop="onToggle(row)">
                  {{ row.enabled ? '停用' : '启用' }}
                </el-button>
                <el-button link type="danger" :loading="deleting === row.modelName" @click.stop="onDelete(row)">删除</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!loading && models.length === 0" description="暂无模型，请先在上方新增" />
      </div>
    </template>

    <el-dialog v-model="dialogVisible" title="模型连通性测试" width="640px">
      <el-form label-width="100px">
        <el-form-item label="模型名称">
          <el-input :model-value="form.modelName" disabled />
        </el-form-item>
        <el-form-item label="测试提示词">
          <el-input v-model="testForm.prompt" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="超时(ms)">
          <el-input-number v-model="testForm.timeoutMs" :min="1000" :max="120000" />
        </el-form-item>
      </el-form>

      <div v-if="testResult" class="rounded-lg border border-border p-3 bg-slate-50 dark:bg-slate-900">
        <div class="text-sm">
          <span class="font-medium">结果：</span>
          <el-tag :type="testResult.ok ? 'success' : 'danger'">{{ testResult.ok ? '成功' : '失败' }}</el-tag>
        </div>
        <div class="text-xs text-text-tertiary mt-2">耗时：{{ testResult.latencyMs }}ms</div>
        <div class="text-xs text-text-tertiary mt-1">Provider：{{ testResult.provider || '-' }}</div>
        <div v-if="testResult.ok" class="mt-2 text-sm break-all">{{ testResult.outputPreview || '' }}</div>
        <div v-else class="mt-2 text-sm text-danger break-all">
          {{ testResult.errorType || 'unknown' }}: {{ testResult.errorMessage || '-' }}
        </div>
      </div>

      <template #footer>
        <el-button @click="dialogVisible = false">关闭</el-button>
        <el-button type="primary" :loading="testing" @click="onRunConnectivityTest">开始测试</el-button>
      </template>
    </el-dialog>
  </div>
</template>
