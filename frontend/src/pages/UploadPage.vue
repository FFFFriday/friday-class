<script setup>
import { ref } from 'vue'
import http from '@/api/http'

const file = ref(null)
const fileInput = ref(null)
const uploading = ref(false)
const message = ref('')
const failed = ref(false)
const dragging = ref(false)

const PPTX_RE = /\.pptx$/i

/**
 * 只接受 .pptx。
 * accept=".pptx" 只约束「选择文件」对话框，用户把它切成「所有文件」就绕过去了；
 * 拖拽进来的更是完全不受 accept 约束。所以两条路径都要自己校验。
 */
function acceptFile(picked) {
  if (!picked) {
    file.value = null
    return
  }
  if (!PPTX_RE.test(picked.name)) {
    file.value = null
    failed.value = true
    message.value = `只支持 .pptx 文件，当前选的是：${picked.name}`
    return
  }
  file.value = picked
  message.value = ''
  failed.value = false
}

function onFileChange(e) {
  acceptFile(e.target.files && e.target.files[0])
}

/** 拖拽进来的文件直接当选中（原生 input 塞不回去，所以单独存在 file 里） */
function onDrop(e) {
  dragging.value = false
  acceptFile(e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0])
}

/**
 * 选文件前先把原生 input 的 value 清空。
 * 不清的话，用户再次选中**同一个文件**时 value 没变，浏览器不派发 change，
 * 看起来就是「点了没反应」。清 value 不会取消这次选择，所以直接放行默认行为。
 */
function beforePick() {
  if (fileInput.value) {
    fileInput.value.value = ''
  }
}

/** 清空已选文件（含原生 input 的 value，否则状态不同步） */
function resetFile() {
  file.value = null
  if (fileInput.value) {
    fileInput.value.value = ''
  }
}

async function upload() {
  if (!file.value) {
    message.value = '请先选择 .pptx 文件'
    failed.value = true
    return
  }
  uploading.value = true
  message.value = ''
  failed.value = false
  try {
    const formData = new FormData()
    formData.append('file', file.value)
    // 单独放宽超时：后端解析 .pptx 是同步的，大课件要几十秒
    const data = await http.post('/courseware/upload', formData, { timeout: 180000 })
    const pages = data.pageCount ? `，共 ${data.pageCount} 页` : ''
    message.value = `上传成功：${data.name}${pages}`
    resetFile()
  } catch (e) {
    failed.value = true
    message.value = e.message || '上传失败'
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <div class="upload">
    <h1 class="heading">上传课件</h1>
    <div class="box">
      <!--
        用 <label for> 包住，而不是 div + role=button + JS 调 input.click()：
        浏览器**原生**就会把点击转成「打开选择文件对话框」，完全不依赖 JS。
        少一层 JS 就少一类「点了不弹窗」的可能，也省掉 input.click() 冒泡回 label
        导致处理函数被调两次的重复触发。
      -->
      <label
        class="dropzone"
        :class="{ 'is-dragging': dragging, 'has-file': !!file }"
        for="pptx-input"
        @click="beforePick"
        @dragover.prevent="dragging = true"
        @dragleave.prevent="dragging = false"
        @drop.prevent="onDrop"
      >
        <span class="dz-title">{{ file ? file.name : '点击这里选择 .pptx 文件' }}</span>
        <span class="dz-sub">{{ file ? '点这里可以换一个文件' : '也可以把文件直接拖进来' }}</span>
        <!--
          视觉上隐藏但**保留在无障碍树里**（不是 display:none）：
          这样键盘 Tab 能聚焦到它、回车能打开对话框，屏幕阅读器也能播报。
        -->
        <input
          id="pptx-input"
          ref="fileInput"
          class="dz-input"
          type="file"
          accept=".pptx"
          @change="onFileChange"
          @click.stop
        />
      </label>

      <button class="btn" :disabled="uploading || !file" @click="upload">
        {{ uploading ? '上传中…' : '开始上传' }}
      </button>

      <p
        v-if="message"
        class="msg"
        :class="{ 'msg-error': failed }"
        :role="failed ? 'alert' : 'status'"
        aria-live="polite"
      >
        {{ message }}
      </p>
      <p class="hint">仅支持 .pptx 文字型课件，上传后将自动转换网页幻灯片并逐页解析知识点。</p>
    </div>
  </div>
</template>

<style scoped>
.upload {
  max-width: 640px;
  margin: 0 auto;
}
.heading {
  font-size: 22px;
  margin-bottom: 16px;
}
.box {
  background: #fff;
  border: 1px solid #eee;
  border-radius: 8px;
  padding: 28px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.dropzone {
  position: relative;
  display: block;
  border: 2px dashed #ddd;
  border-radius: 8px;
  padding: 32px 20px;
  text-align: center;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.dropzone:hover,
.dropzone:focus-within {
  border-color: #d97757;
  background: #fffaf8;
}
.dropzone.is-dragging {
  border-color: #d97757;
  background: #fff3ee;
}
.dropzone.has-file {
  border-style: solid;
  border-color: #d97757;
}
.dz-title {
  display: block;
  font-size: 15px;
  color: #333;
  font-weight: 500;
  word-break: break-all;
}
.dz-sub {
  display: block;
  margin-top: 6px;
  font-size: 13px;
  color: #999;
}
/* 视觉隐藏但保留可聚焦 / 可被屏幕阅读器播报（不能用 display:none） */
.dz-input {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
  border: 0;
}
.btn {
  padding: 10px 18px;
  border: none;
  border-radius: 6px;
  background: #d97757;
  color: #fff;
  cursor: pointer;
  font-size: 14px;
  align-self: flex-start;
}
.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.msg {
  color: #27ae60;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-all;
}
.msg-error {
  color: #e74c3c;
}
.hint {
  color: #999;
  font-size: 13px;
}
</style>
