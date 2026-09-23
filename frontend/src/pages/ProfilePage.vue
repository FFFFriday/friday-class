<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import http from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { showToast } from '@/composables/useToast'

const auth = useAuthStore()
const router = useRouter()

const user = ref(null)
const loading = ref(false)
const error = ref('')

/** 角色 → 中文。原来写成 `role === 'TEACHER' ? '教师' : '学生'`，把管理员也显示成「学生」。 */
const ROLE_TEXT = { TEACHER: '教师', ADMIN: '管理员', STUDENT: '学生' }
const roleLabel = computed(() => ROLE_TEXT[user.value?.role] || '学生')

// ── 修改名字 ────────────────────────────────────────────────
// 只能改**昵称**。用户名是登录凭据（JWT 主体 + 唯一索引），后端也不给改。
const nickname = ref('')
const savingName = ref(false)

// ── 修改密码 ────────────────────────────────────────────────
// 三个框：原密码 / 新密码 / 再次输入新密码。
// 「再次输入」只是防打错，**不能替代原密码** —— 去掉原密码校验的话，
// 任何拿到登录态的人（共用电脑没退出、令牌被偷）都能直接改密码把原主人锁在外面。
const pwd = ref({ oldPassword: '', newPassword: '', confirmPassword: '' })
const savingPwd = ref(false)

/** 两次新密码不一致。只在「再次输入」填了东西时才提示，避免边输边报红。 */
const pwdMismatch = computed(
  () => !!pwd.value.confirmPassword && pwd.value.newPassword !== pwd.value.confirmPassword,
)

function resetPwdFields() {
  pwd.value = { oldPassword: '', newPassword: '', confirmPassword: '' }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    user.value = await http.get('/auth/me')
    nickname.value = user.value?.nickname || ''
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
    // 渲染出现之后显式清一次：浏览器（尤其 Chrome）看到 type=password 的框，
    // 可能替你自动填上保存过的密码。共用电脑上这既是隐私问题，
    // 也会让「原密码」看起来像已经写好了 —— 打开页面时它必须是空的。
    resetPwdFields()
  }
}

async function saveName() {
  const next = nickname.value.trim()
  if (!next) {
    showToast('名字不能为空', 'warning')
    return
  }
  savingName.value = true
  try {
    const updated = await http.put('/auth/profile', { nickname: next })
    user.value = updated
    // 顶栏读的是 store，不刷新它名字不会跟着变。
    // setSession 是 store 里唯一同时更新内存与 localStorage 的入口，直接复用它。
    auth.setSession(auth.token, updated)
    showToast('名字已修改', 'success')
  } catch (e) {
    showToast(e.message || '修改失败', 'error')
  } finally {
    savingName.value = false
  }
}

async function changePwd() {
  const { oldPassword, newPassword, confirmPassword } = pwd.value
  if (!oldPassword || !newPassword || !confirmPassword) {
    showToast('请填写完整', 'warning')
    return
  }
  if (newPassword !== confirmPassword) {
    showToast('两次输入的新密码不一致', 'warning')
    return
  }
  savingPwd.value = true
  try {
    await http.put('/auth/password', { oldPassword, newPassword })
    resetPwdFields()
    // 后端改密码时会把 token_version +1，而 JwtAuthenticationFilter 会校验这个版本号 ——
    // **当前这个 JWT 此刻已经失效了**。若留在页面上，之后点任何东西都会被动 401 弹回登录页，
    // 用户只会觉得「莫名其妙掉线」。所以这里主动说清楚，并直接送他去登录页。
    showToast('密码已修改，请用新密码重新登录', 'success')
    auth.logout()
    router.push({ name: 'login' })
  } catch (e) {
    showToast(e.message || '修改失败', 'error')
  } finally {
    savingPwd.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="profile">
    <h1 class="heading">账户中心</h1>

    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="error" class="empty error-text">{{ error }}</div>

    <template v-else>
      <div class="card">
        <p v-if="user" class="who">
          <strong>{{ user.nickname || user.username }}</strong>
          <span class="role">{{ roleLabel }}</span>
        </p>
        <p v-if="user" class="meta">用户名：{{ user.username }}（登录用，不可修改）</p>
      </div>

      <div class="card">
        <h2 class="sub">修改名字</h2>
        <p class="tip">这是显示用的名字，可以随便改，不影响登录。</p>
        <!-- 用真 <form> 而不是 <div>：这样在输入框里按**回车**就能提交。
             按钮上的 type="button" 不能省 —— 它默认是 submit，有了 form 之后
             点一下会先触发 @submit 再触发 @click，等于提交两次。 -->
        <form class="form" @submit.prevent="saveName">
          <input
            v-model="nickname"
            type="text"
            maxlength="50"
            placeholder="你的名字"
            aria-label="名字"
          />
          <button class="btn" type="button" :disabled="savingName" @click="saveName">
            {{ savingName ? '保存中…' : '保存' }}
          </button>
        </form>
      </div>

      <div class="card">
        <h2 class="sub">修改密码</h2>
        <form class="form" @submit.prevent="changePwd">
          <!--
            autocomplete 三个框各不相同，不是随手写的：
            · 原密码用 off —— 它是**已存在**的密码，但我们恰恰要它别被自动填上；
            · 两个新密码用 new-password —— 告诉浏览器这是一组新密码，
              它既不会拿旧的来填，也不会多弹「更新密码」的提示。
          -->
          <input
            v-model="pwd.oldPassword"
            type="password"
            autocomplete="off"
            placeholder="原密码"
            aria-label="原密码"
          />
          <input
            v-model="pwd.newPassword"
            type="password"
            autocomplete="new-password"
            placeholder="新密码（6~64 位）"
            aria-label="新密码"
          />
          <input
            v-model="pwd.confirmPassword"
            type="password"
            autocomplete="new-password"
            placeholder="再次输入新密码"
            aria-label="再次输入新密码"
            :class="{ 'input--bad': pwdMismatch }"
          />
          <p v-if="pwdMismatch" class="warn" role="alert">两次输入的新密码不一致</p>
          <button class="btn" type="button" :disabled="savingPwd || pwdMismatch" @click="changePwd">
            {{ savingPwd ? '提交中…' : '确认修改' }}
          </button>
        </form>
      </div>
    </template>
  </div>
</template>

<style scoped>
.profile {
  max-width: 560px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.heading {
  font-size: 22px;
}
.card {
  background: var(--fc-bg-panel);
  border: 1px solid var(--fc-border);
  border-radius: 8px;
  padding: 20px 24px;
}
.who {
  font-size: 16px;
  display: flex;
  align-items: center;
  gap: 8px;
}
/* 角色徽章是静态信息，不是「可操作」——所以不挂朱色，
   用墨的最浅底 + 墨字（--fc-primary-bg 就是原 #fff3e6 的对位令牌）。 */
.role {
  font-size: 12px;
  color: var(--fc-primary);
  background: var(--fc-primary-bg);
  padding: 2px 8px;
  border-radius: 20px;
}
.meta {
  color: var(--fc-text-faint);
  font-size: 13px;
  margin-top: 6px;
}
.sub {
  font-size: 15px;
  margin-bottom: 12px;
}
.tip {
  font-size: 13px;
  color: var(--fc-text-faint);
  margin-bottom: 12px;
}
.form {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.form input {
  padding: 9px 12px;
  border: 1px solid var(--fc-border-strong);
  border-radius: 6px;
  font-size: 14px;
}
.form input:focus {
  outline: none;
  border-color: var(--fc-accent);
}
/* 两次密码不一致时给输入框本身一个红边，比只在下面写一行字更容易被看到 */
.form input.input--bad {
  border-color: var(--fc-danger);
}
.warn {
  font-size: 13px;
  color: var(--fc-danger);
}
.btn {
  padding: 9px 16px;
  border: none;
  border-radius: 6px;
  background: var(--fc-primary);
  color: var(--fc-text-invert);
  cursor: pointer;
  font-size: 14px;
  align-self: flex-start;
}
.btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.empty {
  color: var(--fc-text-faint);
  text-align: center;
  padding: 40px 0;
}
.error-text {
  color: var(--fc-danger);
}
</style>
