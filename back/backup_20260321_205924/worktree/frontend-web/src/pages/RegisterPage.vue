<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { showApiError } from '@/api/http'

const router = useRouter()
const auth = useAuthStore()

const form = ref({ username: '', password: '', phone: '' })
const isSubmitting = ref(false)

const onRegister = async () => {
  const username = form.value.username.trim()
  const password = form.value.password
  const phone = form.value.phone.trim()
  if (!username) {
    ElMessage.warning('请输入用户名')
    return
  }
  if (password.length < 6) {
    ElMessage.warning('密码至少 6 位')
    return
  }
  if (phone && !/^1\d{10}$/.test(phone)) {
    ElMessage.warning('请输入正确的手机号')
    return
  }

  isSubmitting.value = true
  try {
    await auth.register({ username, password, phone: phone || undefined })
    ElMessage.success('注册成功，请登录')
    await router.replace({ name: 'login' })
  } catch (e) {
    showApiError(e)
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <div class="min-h-dvh bg-slate-50">
    <div class="mx-auto flex min-h-dvh max-w-lg items-center px-4">
      <div class="w-full rounded-3xl border bg-white p-6">
        <div class="text-lg font-semibold">注册</div>
        <div class="mt-1 text-sm text-slate-500">创建账号后即可开始生成你的毕设项目</div>

        <el-form class="mt-5" label-position="top">
          <el-form-item label="用户名">
            <el-input v-model="form.username" placeholder="请输入用户名" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="form.password" type="password" show-password placeholder="至少 6 位" />
          </el-form-item>
          <el-form-item label="手机号（可选）">
            <el-input v-model="form.phone" placeholder="用于验证码登录" />
          </el-form-item>

          <el-button class="w-full" type="primary" :loading="isSubmitting" @click="onRegister">注册</el-button>
        </el-form>

        <div class="mt-4 flex items-center justify-between text-sm">
          <div class="text-slate-500">已有账号？</div>
          <el-button type="primary" link @click="router.push({ name: 'login' })">去登录</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

