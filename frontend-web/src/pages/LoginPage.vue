<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { showApiError } from '@/api/http'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const active = ref<'password' | 'sms'>('password')
const isSubmitting = ref(false)

const loginForm = ref({ username: '', password: '' })
const smsForm = ref({ phone: '', captcha: '' })

const countdown = ref(0)
let timer: number | null = null

const redirect = computed(() => String(route.query.redirect ?? '/home'))

const startCountdown = () => {
  countdown.value = 60
  if (timer) window.clearInterval(timer)
  timer = window.setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0 && timer) {
      window.clearInterval(timer)
      timer = null
    }
  }, 1000)
}

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})

const onLogin = async () => {
  isSubmitting.value = true
  try {
    await auth.login({ username: loginForm.value.username.trim(), password: loginForm.value.password })
    ElMessage.success('登录成功')
    await router.replace(redirect.value)
  } catch (e) {
    showApiError(e)
  } finally {
    isSubmitting.value = false
  }
}

const onSendSms = async () => {
  const phone = smsForm.value.phone.trim()
  if (!/^1\d{10}$/.test(phone)) {
    ElMessage.warning('请输入正确的手机号')
    return
  }
  try {
    await auth.smsSend({ phone })
    ElMessage.success('验证码已发送')
    startCountdown()
  } catch (e) {
    showApiError(e)
  }
}

const onLoginSms = async () => {
  const phone = smsForm.value.phone.trim()
  const captcha = smsForm.value.captcha.trim()
  if (!/^1\d{10}$/.test(phone)) {
    ElMessage.warning('请输入正确的手机号')
    return
  }
  if (!captcha) {
    ElMessage.warning('请输入验证码')
    return
  }
  isSubmitting.value = true
  try {
    await auth.loginSms({ phone, captcha })
    ElMessage.success('登录成功')
    await router.replace(redirect.value)
  } catch (e) {
    showApiError(e)
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <div class="min-h-dvh bg-slate-50">
    <div class="mx-auto flex min-h-dvh max-w-5xl items-center px-4">
      <div class="grid w-full grid-cols-1 gap-6 md:grid-cols-2">
        <div class="rounded-3xl border bg-white p-6">
          <div class="flex items-center gap-3">
            <div class="h-10 w-10 rounded-2xl bg-blue-600" />
            <div>
              <div class="text-lg font-semibold">智研方舟</div>
              <div class="text-sm text-slate-500">毕业设计 AI 项目生成平台</div>
            </div>
          </div>

          <div class="mt-6 text-sm text-slate-600">
            先用中文描述需求，我会通过对话帮你梳理功能清单与数据库设计，然后再选择技术栈一键生成可运行工程。
          </div>
          <div class="mt-4 text-xs text-slate-500">提示：AI 生成内容仅供参考，请自行理解与完善。</div>
        </div>

        <div class="rounded-3xl border bg-white p-6">
          <div class="text-base font-semibold">登录</div>
          <div class="mt-4">
            <el-tabs v-model="active" stretch>
              <el-tab-pane label="账号密码" name="password">
                <el-form class="mt-2" label-position="top">
                  <el-form-item label="用户名">
                    <el-input v-model="loginForm.username" placeholder="请输入用户名" />
                  </el-form-item>
                  <el-form-item label="密码">
                    <el-input v-model="loginForm.password" type="password" show-password placeholder="请输入密码" />
                  </el-form-item>
                  <el-button class="w-full" type="primary" :loading="isSubmitting" @click="onLogin">
                    登录
                  </el-button>
                </el-form>
              </el-tab-pane>

              <el-tab-pane label="手机验证码" name="sms">
                <el-form class="mt-2" label-position="top">
                  <el-form-item label="手机号">
                    <el-input v-model="smsForm.phone" placeholder="请输入手机号" />
                  </el-form-item>
                  <el-form-item label="验证码">
                    <div class="flex w-full gap-2">
                      <el-input v-model="smsForm.captcha" placeholder="6位验证码" />
                      <el-button :disabled="countdown > 0" @click="onSendSms">
                        {{ countdown > 0 ? `${countdown}s` : '发送' }}
                      </el-button>
                    </div>
                  </el-form-item>
                  <el-button class="w-full" type="primary" :loading="isSubmitting" @click="onLoginSms">
                    登录
                  </el-button>
                </el-form>
              </el-tab-pane>
            </el-tabs>
          </div>

          <div class="mt-4 flex items-center justify-between text-sm">
            <div class="text-slate-500">还没有账号？</div>
            <el-button type="primary" link @click="router.push({ name: 'register' })">去注册</el-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
