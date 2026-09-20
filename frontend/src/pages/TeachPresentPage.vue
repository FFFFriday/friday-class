<script setup>
// 纯净演示页（/present/:sessionId）—— 老师**共享的就是这个标签页**。
//
// 【为什么单独做一个页面，而不是复用教师控制台】
// 老师共享出去的是整个标签页，如果共享的是控制台，学生就会看到
// 「上一页 / 下一页 / 下课」这些按钮，以及课堂标题栏。
// 所以这里只有幻灯片本身 + 一个角落页码，**没有任何按钮**。
//
// 【翻页仍然走 POST，不走 WebSocket】
// 沿用既有设计取舍：WS 一断老师就翻不了页，而 POST 只要网络通就行。
// 页码由这个页面自己产生，所以「学生看到的页码」和「AI 用的知识点」
// 天然对齐，不存在识别不准的问题。

import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import http from '@/api/http'

const route = useRoute()

const session = ref(null)
const totalPages = ref(0)
const currentPage = ref(1)
const loadError = ref('')
const jumping = ref(false)
const imageFailed = ref(false)

const slideImageUrl = computed(() =>
  session.value ? `/slides/${session.value.coursewareId}/page${currentPage.value}.png` : null,
)

const slideTextUrl = computed(() =>
  session.value ? `/slides/${session.value.coursewareId}/page${currentPage.value}.html` : null,
)

// 换页要清掉回退标记，否则某一页渲染失败后，后面的页会一直被锁在文字版
watch(currentPage, () => {
  imageFailed.value = false
})

async function load() {
  loadError.value = ''
  try {
    const data = await http.get(`/session/${route.params.sessionId}`)
    session.value = data
    currentPage.value = data.currentPage ?? 1

    const pageList = await http.get(`/courseware/${data.coursewareId}/pages`)
    totalPages.value = (pageList.list || []).length
  } catch (e) {
    loadError.value = e.message || '加载失败'
    session.value = null
  }
}

async function goTo(pageNo) {
  if (!session.value || jumping.value) return
  if (pageNo < 1) return
  if (totalPages.value > 0 && pageNo > totalPages.value) return

  jumping.value = true
  try {
    // 服务端「先落库、再广播」，所以这里拿到响应时，学生的页码已经在切了
    const data = await http.post(`/session/${route.params.sessionId}/page`, { pageNo })
    currentPage.value = data.currentPage ?? pageNo
  } catch {
    // 演示页**刻意不显示错误**：这是要被共享出去的画面，
    // 弹一条红字全班都看得见，比翻页失败本身更难看。
    // 失败时页码不动，老师再按一次即可。
  } finally {
    jumping.value = false
  }
}

function next() {
  goTo(currentPage.value + 1)
}

function prev() {
  goTo(currentPage.value - 1)
}

function onKeydown(event) {
  if (event.key === 'ArrowRight' || event.key === 'PageDown' || event.key === ' ') {
    event.preventDefault()
    next()
  } else if (event.key === 'ArrowLeft' || event.key === 'PageUp') {
    event.preventDefault()
    prev()
  }
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => window.removeEventListener('keydown', onKeydown))

watch(() => route.params.sessionId, load, { immediate: true })
</script>

<template>
  <div class="present">
    <p v-if="loadError" class="present__error">{{ loadError }}</p>

    <template v-else-if="session">
      <img
        v-if="slideImageUrl && !imageFailed"
        class="present__slide"
        :src="slideImageUrl"
        :alt="`第 ${currentPage} 页`"
        @error="imageFailed = true"
      />
      <iframe
        v-else
        class="present__slide"
        :src="slideTextUrl"
        :title="`第 ${currentPage} 页（文字版）`"
      ></iframe>

      <!--
        左右两块不可见的点击区：演示页不能有按钮（会被共享出去），
        但鼠标翻页仍然要有。左边 18% 是上一页，其余是下一页。
      -->
      <button class="present__zone present__zone--prev" type="button" tabindex="-1" aria-label="上一页" @click="prev" />
      <button class="present__zone present__zone--next" type="button" tabindex="-1" aria-label="下一页" @click="next" />

      <span class="present__badge">{{ currentPage }} / {{ totalPages || '?' }}</span>
    </template>
  </div>
</template>

<style scoped>
/*
 * 这是要被共享出去的画面，所以铺满整个视口、不留边距、不带背景色，
 * 让幻灯片占据尽可能大的面积。
 */
.present {
  position: fixed;
  inset: 0;
  background: #111;
  overflow: hidden;
}

.present__slide {
  width: 100%;
  height: 100%;
  /* contain 而不是 cover：宁可留黑边，也不能把课件裁掉 */
  object-fit: contain;
  display: block;
  border: none;
}

.present__zone {
  position: absolute;
  top: 0;
  bottom: 0;
  background: transparent;
  cursor: pointer;
  border: none;
  padding: 0;
}

.present__zone--prev {
  left: 0;
  width: 18%;
}

.present__zone--next {
  right: 0;
  width: 82%;
}

/* 页码角标。压暗、半透明、不抢视线，但老师能随时确认自己在第几页。 */
.present__badge {
  position: absolute;
  right: 14px;
  bottom: 14px;
  padding: 3px 12px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.45);
  color: rgba(255, 255, 255, 0.75);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  pointer-events: none;
  user-select: none;
}

.present__error {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #999;
  font-size: 14px;
}
</style>
