<script setup>
// 登录页。未登录访客能看到的唯一界面（其余路由全部 requiresAuth）。
//
// 改造前这里最致命的一行是 `localStorage.setItem('token', data.token)`：
// 登录接口明明返回了 user（含 role），却被当场丢掉，导致前端永远不知道
// 当前是教师还是学生，教师和学生看到完全一样的界面。
// 现在统一交给 auth store 存。
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useUserFormRules } from '@/composables/useUserFormRules'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const mode = ref('login') // 'login' | 'register'
const form = ref({ username: '', password: '', nickname: '' })
const loading = ref(false)
/** 服务端返回的整体错误（如「用户名或密码错误」「用户名已存在」）。 */
const error = ref('')

/**
 * 字段级校验：哪个框空着就把红字挂在哪一行。
 * 昵称参与校验但**只查长度**——它是选填的，留空不该标红。
 */
const { errors, checkRequired, checkAll, clearAll, clear } = useUserFormRules([
  'username',
  'password',
  'nickname',
])

const features = ['课前：课件上传与逐页知识点解析', '课中：直播授课与翻页实时同步', '课后：自动生成课程总结']

function switchMode(m) {
  mode.value = m
  error.value = ''
  // 切页面时清空上一模式的校验结果：否则「登录」留下的红字会跟着进「注册」
  clearAll()
}

async function submit() {
  error.value = ''
  clearAll()

  if (mode.value === 'login') {
    // 登录只查非空。长度规则是注册的账号规则，不该在登录页暴露。
    // 两个都查完再判断——用 && 短路的话只会标红第一项，用户得试两次。
    const usernameOk = checkRequired('username', form.value.username, '请输入用户名')
    const passwordOk = checkRequired('password', form.value.password, '请输入密码')
    if (!usernameOk || !passwordOk) return
  } else if (!checkAll(form.value)) {
    return
  }

  loading.value = true
  try {
    if (mode.value === 'login') {
      // 登录：{ token, user: { id, username, role, nickname } }
      const data = await http.post('/auth/login', {
        username: form.value.username,
        password: form.value.password,
      })
      auth.setSession(data.token, data.user)
    } else {
      // 注册返回的是**扁平**结构，没有嵌套 user，要自己拼。
      // （后端 RegisterResponse 是 record(id, username, role, nickname, token)）
      const data = await http.post('/auth/register', form.value)
      auth.setSession(data.token, {
        id: data.id,
        username: data.username,
        role: data.role,
        nickname: data.nickname,
      })
    }

    // redirect 由路由守卫写入。防御一下：万一它本身指向 /login，会变成死循环。
    const target = route.query.redirect
    router.push(target && !String(target).startsWith('/login') ? target : '/')
  } catch (e) {
    error.value = e.message || '操作失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <aside class="brand-side">
      <div class="brand-content">
        <h1 class="brand-name">周五课堂</h1>
        <p class="brand-tagline">智能教学互动平台</p>
        <ul class="feature-list">
          <li v-for="f in features" :key="f">
            <span class="mark" />
            <span>{{ f }}</span>
          </li>
        </ul>
      </div>
    </aside>

    <section class="form-side">
      <div class="card">
        <h2 class="card-title">{{ mode === 'login' ? '欢迎回来' : '创建账号' }}</h2>
        <p class="card-sub">
          {{ mode === 'login' ? '登录后进入你的课堂' : '注册后为学生账号' }}
        </p>

        <!-- type="button" 是必须的：这两个按钮一旦被包进 <form>（或后续把 form 上移），
             默认的 submit 类型会导致点 Tab 直接提交表单 -->
        <div class="tabs" role="tablist">
          <button
            type="button"
            role="tab"
            :aria-selected="mode === 'login'"
            :class="{ active: mode === 'login' }"
            @click="switchMode('login')"
          >
            登录
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="mode === 'register'"
            :class="{ active: mode === 'register' }"
            @click="switchMode('register')"
          >
            注册
          </button>
        </div>

        <!--
          必填标红：红框 + 下方红字，逐字段显示。
          输入时（@input）就把该字段的红字清掉——用户已经在改了，旧提示只会碍眼。
        -->
        <form @submit.prevent="submit" novalidate>
          <label class="field">
            <span class="label">用户名</span>
            <input
              v-model="form.username"
              placeholder="请输入用户名"
              autocomplete="username"
              :class="{ 'input--error': errors.username }"
              :aria-invalid="errors.username ? 'true' : undefined"
              @input="clear('username')"
            />
            <span v-if="errors.username" class="field-error" role="alert">
              {{ errors.username }}
            </span>
          </label>

          <label class="field">
            <span class="label">密码</span>
            <input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
              :class="{ 'input--error': errors.password }"
              :aria-invalid="errors.password ? 'true' : undefined"
              @input="clear('password')"
            />
            <span v-if="errors.password" class="field-error" role="alert">
              {{ errors.password }}
            </span>
          </label>

          <label v-if="mode === 'register'" class="field">
            <span class="label">昵称（选填）</span>
            <input
              v-model="form.nickname"
              placeholder="同学，怎么称呼？"
              :class="{ 'input--error': errors.nickname }"
              :aria-invalid="errors.nickname ? 'true' : undefined"
              @input="clear('nickname')"
            />
            <span v-if="errors.nickname" class="field-error" role="alert">
              {{ errors.nickname }}
            </span>
          </label>

          <p v-if="error" class="error" role="alert">{{ error }}</p>

          <button class="submit" type="submit" :disabled="loading">
            {{ loading ? '提交中…' : mode === 'login' ? '登 录' : '注 册' }}
          </button>
        </form>

        <p v-if="mode === 'register'" class="foot-hint">教师账号由管理员统一开通</p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: flex;
}

/* 左半屏：品牌区。纯渐变 + 排版，不用插图。 */
.brand-side {
  flex: 1;
  display: flex;
  align-items: center;
  padding: 64px;
  background: linear-gradient(140deg, #d97757 0%, #e08d6a 55%, #efa98d 100%);
  color: #fff;
}

.brand-content {
  max-width: 420px;
}

.brand-name {
  font-size: 38px;
  font-weight: 700;
  letter-spacing: 2px;
}

.brand-tagline {
  margin-top: 14px;
  font-size: 16px;
  opacity: 0.92;
  letter-spacing: 1px;
}

.feature-list {
  margin-top: 44px;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.feature-list li {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 14px;
  opacity: 0.95;
}

.mark {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.9);
  flex-shrink: 0;
}

/* 右半屏：表单区 */
.form-side {
  width: 520px;
  flex-shrink: 0;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px;
}

.card {
  width: 100%;
  max-width: 340px;
}

.card-title {
  font-size: 24px;
  color: #333;
}

.card-sub {
  margin-top: 8px;
  font-size: 13px;
  color: #999;
}

.tabs {
  display: flex;
  gap: 24px;
  margin: 26px 0 22px;
  border-bottom: 1px solid #eee;
}

.tabs button {
  border: none;
  background: transparent;
  padding: 0 0 10px;
  cursor: pointer;
  font-size: 15px;
  color: #999;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  transition: color 0.15s, border-color 0.15s;
}

.tabs button.active {
  color: #d97757;
  border-bottom-color: #d97757;
  font-weight: 600;
}

form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.label {
  font-size: 13px;
  color: #666;
}

.field input {
  padding: 11px 13px;
  border: 1px solid #ddd;
  border-radius: 8px;
  font-size: 14px;
  transition: border-color 0.15s;
}

.field input:focus {
  outline: none;
  border-color: #d97757;
}

/* 必填未过：红框。放在 :focus 之后，聚焦时也保持红——不然用户点回来改，
   红框消失、反而不知道是哪一项出问题了 */
.field input.input--error,
.field input.input--error:focus {
  border-color: #e74c3c;
}

.field-error {
  font-size: 12px;
  color: #e74c3c;
  line-height: 1.4;
}

.error {
  color: #e74c3c;
  font-size: 13px;
  line-height: 1.5;
}

.submit {
  margin-top: 6px;
  padding: 12px;
  border: none;
  border-radius: 8px;
  background: #d97757;
  color: #fff;
  font-size: 15px;
  letter-spacing: 1px;
  cursor: pointer;
  transition: background 0.15s;
}

.submit:hover:not(:disabled) {
  background: #c9694a;
}

.submit:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.foot-hint {
  margin-top: 18px;
  font-size: 12px;
  color: #999;
  text-align: center;
}

/* 窄屏：品牌区让位，表单占满 */
@media (max-width: 900px) {
  .brand-side {
    display: none;
  }
  .form-side {
    width: 100%;
    padding: 32px 24px;
  }
}
</style>
