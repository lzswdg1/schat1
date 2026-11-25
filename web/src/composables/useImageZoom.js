/**
 * 图片缩放/拖拽/双击还原 composable
 *
 * 职责单一：只管缩放和拖拽的状态与事件处理，不关心 UI 渲染。
 * 任何需要图片缩放能力的组件都可以复用。
 *
 * 用法：
 *   const { scale, offsetX, offsetY, onWheel, onPointerDown, onDoubleClick, reset } = useImageZoom()
 *   绑定到 img 元素：@wheel="onWheel" @pointerdown="onPointerDown" @dblclick="onDoubleClick"
 *   样式：:style="{ transform: `translate(${offsetX}px, ${offsetY}px) scale(${scale})` }"
 */

import { ref, computed } from 'vue'

const MIN_SCALE = 0.5
const MAX_SCALE = 5
const ZOOM_STEP = 0.15

export function useImageZoom() {
  const scale = ref(1)
  const offsetX = ref(0)
  const offsetY = ref(0)

  // 拖拽状态（不暴露）
  let dragging = false
  let startX = 0
  let startY = 0
  let startOffsetX = 0
  let startOffsetY = 0

  const scalePercent = computed(() => Math.round(scale.value * 100))

  function reset() {
    scale.value = 1
    offsetX.value = 0
    offsetY.value = 0
  }

  function clampScale(v) {
    return Math.min(MAX_SCALE, Math.max(MIN_SCALE, v))
  }

  /**
   * 滚轮缩放（以鼠标位置为锚点）
   */
  function onWheel(e) {
    e.preventDefault()
    const direction = e.deltaY < 0 ? 1 : -1
    const newScale = clampScale(scale.value + direction * ZOOM_STEP)

    // 以鼠标位置为锚点缩放：调整偏移量使鼠标指向的图片位置不变
    const rect = e.currentTarget.getBoundingClientRect()
    const cx = e.clientX - rect.left - rect.width / 2
    const cy = e.clientY - rect.top - rect.height / 2
    const ratio = 1 - newScale / scale.value
    offsetX.value += cx * ratio
    offsetY.value += cy * ratio

    scale.value = newScale
  }

  /**
   * 拖拽开始
   */
  function onPointerDown(e) {
    if (scale.value <= 1) return // 未放大时不允许拖拽
    e.preventDefault()
    dragging = true
    startX = e.clientX
    startY = e.clientY
    startOffsetX = offsetX.value
    startOffsetY = offsetY.value

    const target = e.currentTarget
    target.setPointerCapture(e.pointerId)

    const onMove = (ev) => {
      if (!dragging) return
      offsetX.value = startOffsetX + (ev.clientX - startX)
      offsetY.value = startOffsetY + (ev.clientY - startY)
    }

    const onUp = () => {
      dragging = false
      target.removeEventListener('pointermove', onMove)
      target.removeEventListener('pointerup', onUp)
    }

    target.addEventListener('pointermove', onMove)
    target.addEventListener('pointerup', onUp)
  }

  /**
   * 双击还原
   */
  function onDoubleClick() {
    reset()
  }

  return {
    scale,
    offsetX,
    offsetY,
    scalePercent,
    reset,
    onWheel,
    onPointerDown,
    onDoubleClick
  }
}
