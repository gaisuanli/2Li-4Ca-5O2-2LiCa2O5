<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useStore } from 'vuex'
import { formatApiError } from '../api'

const store = useStore()
const router = useRouter()
const form = reactive({ username: 'supervisor', password: 'Safe@123' })
const submitting = ref(false)
const error = ref('')

async function submit() {
  error.value = ''
  submitting.value = true
  try {
    await store.dispatch('login', form)
    router.replace('/dashboard')
  } catch (reason) {
    error.value = formatApiError(reason, '登录失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-intro">
      <div class="login-folio">01</div>
      <p class="eyebrow">贵州大学实训项目</p>
      <h1>建筑安全<br />智能监控平台</h1>
      <p class="login-lead">汇聚设备、环境、视频与 AI 风险数据，完成从异常识别到处置留痕的监管闭环。</p>
      <dl class="login-capabilities">
        <div><dt>设备接入</dt><dd>TCP 遥测与状态追踪</dd></div>
        <div><dt>安全规则</dt><dd>阈值判断与告警抑制</dd></div>
        <div><dt>处置闭环</dt><dd>确认、解决、关闭与审计</dd></div>
      </dl>
    </section>

    <section class="login-panel" aria-labelledby="login-title">
      <div>
        <p class="eyebrow">用户登录</p>
        <h2 id="login-title">进入监管工作台</h2>
        <p class="form-note">当前环境使用确定性演示数据。</p>
      </div>
      <form @submit.prevent="submit">
        <label>
          <span>用户名</span>
          <input v-model.trim="form.username" name="username" autocomplete="username" required />
        </label>
        <label>
          <span>密码</span>
          <input v-model="form.password" name="password" type="password" autocomplete="current-password" required />
        </label>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <button class="button button-primary login-submit" type="submit" :disabled="submitting">{{ submitting ? '正在登录' : '登录' }}</button>
      </form>
      <div class="demo-accounts">
        <strong>演示账号</strong>
        <span>监管员 supervisor / Safe@123</span>
        <span>设备管理员 device / Device@123</span>
        <span>系统管理员 admin / Admin@123</span>
      </div>
    </section>
  </main>
</template>
