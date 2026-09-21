import { reactive } from 'vue'

/**
 * 用户名 / 密码 / 昵称的表单校验。
 *
 * <h3>为什么要单独抽出来</h3>
 *
 * 原先「必填」只体现为提交后弹一条笼统的红字（如「用户名与密码都要填」），
 * 用户得自己回头看是哪一项没填。Friday 要的是**按字段标红的提示**——
 * 哪个框空着就把红字挂在哪一行。
 *
 * `FcInput` 其实早就有 `error` prop（红框 + 下方红字），一直没人用。
 * 这个模块就是给它供数据的。
 *
 * <h3>文案为什么长这样</h3>
 *
 * 与后端 `AdminCreateUserRequest` / `RegisterRequest` 上的
 * `@NotBlank(message = "用户名不能为空")` 等**逐字对齐**。
 * 前端先拦一道只是为了让提示立刻出现，**不替代后端校验**；
 * 两边文案不一致的话，用户会看到「前端说 3 位就够、后端说不够」这种自相矛盾。
 */

const USERNAME_MIN = 3
const USERNAME_MAX = 50
const PASSWORD_MIN = 6
const PASSWORD_MAX = 64
const NICKNAME_MAX = 50

/**
 * 单字段规则。返回空串表示通过，否则返回要显示在输入框下方的红字。
 *
 * 统一用「先 trim 再判断」：全是空格的用户名会被后端拒掉，
 * 前端不 trim 就会放它过去、然后在服务端报错。
 */
export const FIELD_RULES = {
  username(value) {
    const v = (value ?? '').trim()
    if (!v) {
      return '用户名不能为空'
    }
    if (v.length < USERNAME_MIN || v.length > USERNAME_MAX) {
      return `用户名长度需在 ${USERNAME_MIN}~${USERNAME_MAX} 之间`
    }
    return ''
  },

  password(value) {
    const v = value ?? ''
    if (!v) {
      return '密码不能为空'
    }
    if (v.length < PASSWORD_MIN || v.length > PASSWORD_MAX) {
      return `密码长度需在 ${PASSWORD_MIN}~${PASSWORD_MAX} 之间`
    }
    return ''
  },

  /**
   * 昵称**不是必填**（后端 `@Size` 而非 `@NotBlank`），所以只查长度、不查空。
   * 空串在这里是合法输入，不要给它标红。
   */
  nickname(value) {
    const v = (value ?? '').trim()
    if (v.length > NICKNAME_MAX) {
      return `昵称最长 ${NICKNAME_MAX} 个字符`
    }
    return ''
  },

  /** 登录页专用：只要求非空，长度规则留给后端判（登录不该暴露账号规则）。 */
  required(value) {
    return (value ?? '').trim() ? '' : '不能为空'
  },
}

/**
 * 建一组响应式的字段错误。
 *
 * @param {string[]} fields 要管哪些字段，如 `['username', 'password']`
 */
export function useUserFormRules(fields = ['username', 'password']) {
  // 用普通对象保证每个键都存在：模板里直接读 errors.username，
  // 键不存在会变成 undefined 而不是 ''，FcInput 的 error prop 类型校验会报警告
  const errors = reactive(Object.fromEntries(fields.map((f) => [f, ''])))

  /** 校验单个字段并写回错误。@returns {boolean} 是否通过 */
  function check(field, value) {
    const rule = FIELD_RULES[field]
    errors[field] = rule ? rule(value) : ''
    return !errors[field]
  }

  /**
   * 只查「有没有填」，用调用方给的文案。
   *
   * 登录页专用：登录只要非空即可，**不该**套用注册的长度规则
   * （「用户名长度需在 3~50 之间」这种提示，等于在登录页告诉别人账号规则长什么样）。
   *
   * @returns {boolean} 是否通过
   */
  function checkRequired(field, value, message) {
    errors[field] = (value ?? '').trim() ? '' : message
    return !errors[field]
  }

  /** 校验一批字段（按顺序，全部跑完而不是遇到第一个错就停）。@returns {boolean} 全部是否通过 */
  function checkAll(form, target = fields) {
    let ok = true
    for (const field of target) {
      if (!check(field, form[field])) {
        ok = false
      }
    }
    return ok
  }

  /** 清空全部错误（打开弹窗、关闭弹窗时调用）。 */
  function clearAll() {
    for (const field of Object.keys(errors)) {
      errors[field] = ''
    }
  }

  /** 清单个字段——用户开始改这个框了，之前那条红字就该消失。 */
  function clear(field) {
    if (field in errors) {
      errors[field] = ''
    }
  }

  return { errors, check, checkRequired, checkAll, clearAll, clear }
}
