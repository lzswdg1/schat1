<template>
  <Teleport to="body">
    <div
      v-if="visible"
      class="context-menu-overlay"
      @click="handleClose"
      @contextmenu.prevent="handleClose"
    >
      <div
        class="context-menu"
        :style="menuStyle"
        v-motion
        :initial="{ opacity: 0, scale: 0.88, y: -4 }"
        :enter="{ opacity: 1, scale: 1, y: 0, transition: { type: 'spring', stiffness: 400, damping: 22 } }"
        @click.stop
      >
        <div
          v-for="(item, index) in items"
          :key="index"
          class="context-menu__item"
          :class="{ 'context-menu__item--danger': item.danger }"
          @click="handleSelect(item)"
        >
          {{ item.label }}
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  items: {
    type: Array,
    default: () => []
  },
  position: {
    type: Object,
    default: () => ({ x: 0, y: 0 })
  },
  visible: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['select', 'close'])

const menuStyle = computed(() => ({
  left: `${props.position.x}px`,
  top: `${props.position.y}px`
}))

function handleSelect(item) {
  emit('select', item)
  emit('close')
}

function handleClose() {
  emit('close')
}
</script>

<style lang="scss" scoped>
.context-menu-overlay {
  position: fixed;
  inset: 0;
  z-index: var(--z-dropdown);
}

.context-menu {
  position: fixed;
  min-width: 120px;
  padding: var(--spacing-xs) 0;
  background-color: var(--bg-white);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-context-menu);
  z-index: var(--z-dropdown);
}

.context-menu__item {
  padding: var(--spacing-sm) var(--spacing-lg);
  font-size: var(--font-size-md);
  color: var(--text-primary);
  cursor: pointer;
  transition: background-color 200ms cubic-bezier(0.22, 1, 0.36, 1),
              transform 200ms cubic-bezier(0.22, 1, 0.36, 1);
  white-space: nowrap;

  &:hover {
    background-color: var(--bg-hover);
  }

  &:active {
    transform: scale(0.97);
    transition-duration: 80ms;
  }

  &--danger {
    color: var(--text-danger);
  }
}
</style>
