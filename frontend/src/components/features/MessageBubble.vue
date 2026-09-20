<script setup>
defineProps({
  /** 'q' 学生提问（靠右） / 'a' 助教回答（靠左） */
  role: { type: String, default: 'a' },
  text: { type: String, default: '' },
  /** FAILED 的回答用不同配色——学生/老师要能看出「这条 AI 没答上」 */
  status: { type: String, default: '' },
})

function formatTime(value) {
  if (!value) return ''
  // 后端给的是本地时间的 ISO 串（无时区后缀）。用字符串切片取时分，
  // 不做时区转换——直接 new Date() 解析这种串在部分浏览器上会得到 Invalid Date。
  const match = /T(\d{2}):(\d{2})/.exec(String(value))
  return match ? `${match[1]}:${match[2]}` : ''
}
</script>

<template>
  <div class="bubble" :class="[`bubble--${role}`, { 'bubble--failed': status === 'FAILED' }]">
    <!--
      ⚠️ 安全铁律：这里是**文本插值**，严禁改成 v-html。
      内容里既有学生自由输入，也有模型输出，用 v-html 等于开了 XSS 后门。
    -->
    <p class="bubble__text">{{ text }}</p>
  </div>
</template>

<style scoped>
.bubble {
  display: flex;
  max-width: 100%;
}

.bubble--q {
  justify-content: flex-end;
}

.bubble__text {
  margin: 0;
  padding: var(--fc-space-3) var(--fc-space-4);
  border-radius: var(--fc-radius-lg);
  font-size: var(--fc-font);
  line-height: 1.7;
  /* 保留换行：AI 回答里的 1. / 2. / 3. 分点是它自己换的行 */
  white-space: pre-wrap;
  /* 长 URL / 长英文串不换行会把版面撑破 */
  overflow-wrap: anywhere;
}

.bubble--q .bubble__text {
  background: var(--fc-primary);
  color: var(--fc-text-invert);
  border-bottom-right-radius: var(--fc-radius-sm);
}

.bubble--a .bubble__text {
  background: var(--fc-bg-panel);
  border: 1px solid var(--fc-border);
  color: var(--fc-text);
  border-bottom-left-radius: var(--fc-radius-sm);
}

/* AI 没答上时用暖色而不是红色：它不是报错，是「这条没答出来」 */
.bubble--failed .bubble__text {
  background: var(--fc-warning-bg);
  border-color: var(--fc-warning-border);
  color: var(--fc-warning-text);
}
</style>
