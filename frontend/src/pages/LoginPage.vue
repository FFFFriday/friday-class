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
    <!-- 左半屏 = 讲义封面。
         不放假插图、不放假文案：只有刊头、刊名、一条起手线、一行说明。
         右上角那个「01」是页码脊的起点——这个产品里页码是贯穿全链的主键，
         所以它从第一屏就该在场。 -->
    <aside class="cover">
      <span class="cover__folio fc-num" aria-hidden="true">01</span>

      <div class="cover__block">
        <h1 class="cover__name fc-display">周五课堂</h1>
        <span class="cover__rule" aria-hidden="true"></span>
        <p class="cover__tagline">智能教学互动平台</p>
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
  background: var(--fc-bg);
}

/* ── 左半屏：讲义封面 ─────────────────────────────────────── */
/* 原来这里是一整块陶土橙渐变 + 三条功能列表。
   渐变是整站最像模板的一处，功能列表没人读——两样都砍了。
   留下的是一张封面该有的东西：页码、刊名、起手线、说明。 */

.cover {
  flex: 1;
  position: relative;
  display: flex;
  align-items: center;
  padding: 64px;
  /* 封面比内容区再深一档，靠一条发丝线与表单分开，不用渐变不用阴影 */
  background: var(--fc-bg-muted);
  border-right: 1px solid var(--fc-border);
}

/* 封面页码。等宽字体 + 等宽数字，故意做成「活页边签」的样子。 */
.cover__folio {
  position: absolute;
  top: 40px;
  left: 64px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 44px;
  height: 44px;
  padding: 0 10px;
  border: 1px solid var(--fc-border-strong);
  border-radius: var(--fc-radius-sm);
  background: var(--fc-bg-panel);
  font-size: 15px;
  letter-spacing: 0.04em;
  color: var(--fc-accent);
}

.cover__block {
  max-width: 420px;
}

.cover__name {
  font-size: 46px;
  font-weight: var(--fc-weight-bold);
  line-height: 1.15;
  letter-spacing: 0.12em;
  color: var(--fc-ink);
  /* 中文衬线带字距，是「封面刊名」的排法 */
  text-indent: 0.12em; /* 抵消末字右侧字距，让视觉左边距对齐 */
}

/* 起手线。比发丝线粗一点、短一点，像盖下去的一笔。 */
.cover__rule {
  display: block;
  width: 48px;
  height: 3px;
  margin: 30px 0 18px;
  background: var(--fc-accent);
}

.cover__tagline {
  font-size: 15px;
  letter-spacing: 0.18em;
  color: var(--fc-text-muted);
  margin: 0;
}

/* ── 右半屏：表单区 ───────────────────────────────────────── */

.form-side {
  width: 520px;
  flex-shrink: 0;
  background: var(--fc-bg-panel);
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
  color: var(--fc-text);
}

.card-sub {
  margin-top: 8px;
  font-size: 13px;
  color: var(--fc-text-faint);
}

.tabs {
  display: flex;
  gap: 24px;
  margin: 26px 0 22px;
  border-bottom: 1px solid var(--fc-border);
}

.tabs button {
  padding: 0 0 10px;
  font-size: 15px;
  color: var(--fc-text-faint);
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  transition: color var(--fc-transition), border-color var(--fc-transition);
}

/* 当前页签用朱色：朱色的职责就是标「你现在在哪儿 / 能点什么」 */
.tabs button.active {
  color: var(--fc-accent);
  border-bottom-color: var(--fc-accent);
  font-weight: var(--fc-weight-semibold);
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
  color: var(--fc-text-muted);
}

.field input {
  padding: 11px 13px;
  border: 1px solid var(--fc-border-strong);
  border-radius: var(--fc-radius);
  background: var(--fc-bg-panel);
  font-size: 14px;
  transition: border-color var(--fc-transition);
}

.field input:focus {
  outline: none;
  border-color: var(--fc-primary);
}

/* 必填未过：红框。放在 :focus 之后，聚焦时也保持红——不然用户点回来改，
   红框消失、反而不知道是哪一项出问题了 */
.field input.input--error,
.field input.input--error:focus {
  border-color: var(--fc-danger);
}

.field-error {
  font-size: 12px;
  color: var(--fc-danger);
  line-height: 1.4;
}

.error {
  color: var(--fc-danger);
  font-size: 13px;
  line-height: 1.5;
}

/* 主按钮是**墨色实心**，不是朱色。
   朱色和危险红长得近，两个都当填充按钮用，用户会分不清「提交」和「删除」。 */
.submit {
  margin-top: 6px;
  padding: 12px;
  border-radius: var(--fc-radius);
  background: var(--fc-primary);
  color: var(--fc-text-invert);
  font-size: 15px;
  letter-spacing: 0.08em;
  transition: background var(--fc-transition);
}

.submit:hover:not(:disabled) {
  background: var(--fc-primary-hover);
}

.submit:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.foot-hint {
  margin-top: 18px;
  font-size: 12px;
  color: var(--fc-text-faint);
  text-align: center;
}

/* ── 窄屏 ─────────────────────────────────────────────────── */
/* 原来是「封面直接 display:none」——手机上整站就没了品牌。
   改成封面收成顶上一条，刊名与页码都还在。 */

@media (max-width: 900px) {
  .login-wrap {
    flex-direction: column;
  }

  .cover {
    flex: none;
    padding: 24px;
    align-items: flex-start;
    border-right: none;
    border-bottom: 1px solid var(--fc-border);
  }

  .cover__folio {
    position: static;
    min-width: 36px;
    height: 36px;
    font-size: 13px;
  }

  .cover__block {
    margin-top: 16px;
  }

  .cover__name {
    font-size: 26px;
    letter-spacing: 0.08em;
    text-indent: 0.08em;
  }

  .cover__rule {
    margin: 14px 0 10px;
    width: 32px;
    height: 2px;
  }

  .cover__tagline {
    font-size: 13px;
    letter-spacing: 0.12em;
  }

  .form-side {
    width: 100%;
    padding: 32px 24px 48px;
  }
}
</style>
