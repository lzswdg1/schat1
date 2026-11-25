/**
 * 滚动锚定组合函数
 * 封装滚动锚定逻辑：记录加载前 scrollHeight，加载后恢复 scrollTop
 * 以及自动滚动到底部的判断逻辑
 */

import { ref, nextTick } from 'vue'

/**
 * @param {import('vue').Ref<HTMLElement|null>} containerRef - 滚动容器的 ref
 */
export function useScroll(containerRef) {
  const isAtBottom = ref(true)

  /** 判断用户是否在底部附近（允许 50px 误差） */
  function checkAtBottom() {
    const el = containerRef.value
    if (!el) return
    isAtBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 50
  }

  /** 滚动到底部 */
  async function scrollToBottom(smooth = false) {
    await nextTick()
    const el = containerRef.value
    if (!el) return
    el.scrollTo({
      top: el.scrollHeight,
      behavior: smooth ? 'smooth' : 'instant'
    })
    isAtBottom.value = true
  }

  /**
   * 滚动锚定：在加载历史消息前记录 scrollHeight，
   * 加载后恢复 scrollTop 使用户阅读位置不跳动
   */
  function saveScrollPosition() {
    const el = containerRef.value
    if (!el) return 0
    return el.scrollHeight
  }

  async function restoreScrollPosition(prevScrollHeight) {
    await nextTick()
    const el = containerRef.value
    if (!el) return
    const newScrollHeight = el.scrollHeight
    el.scrollTop = newScrollHeight - prevScrollHeight
  }

  return {
    isAtBottom,
    checkAtBottom,
    scrollToBottom,
    saveScrollPosition,
    restoreScrollPosition
  }
}
