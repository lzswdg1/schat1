<template>
  <div class="message-input">
    <!-- Quote preview -->
    <div v-if="chatStore.quoteMessage" class="message-input__quote">
      <div class="message-input__quote-info">
        <span class="message-input__quote-label">引用</span>
        <span class="message-input__quote-sender">{{ chatStore.quoteMessage.senderName }}</span>
        <span class="message-input__quote-content">{{ chatStore.quoteMessage.content }}</span>
      </div>
      <button class="message-input__quote-close" title="取消引用" @click="chatStore.clearQuote()">
        <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor">
          <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" />
        </svg>
      </button>
    </div>

    <!-- Image preview -->
    <div v-if="imagePreview" class="message-input__image-preview">
      <img :src="imagePreview" alt="预览" class="message-input__image-thumb" />
      <button class="message-input__image-remove" title="移除图片" @click="clearImage">
        <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor">
          <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" />
        </svg>
      </button>
    </div>

    <!-- File info -->
    <div v-if="selectedFile && !imagePreview" class="message-input__file-info">
      <span class="message-input__file-icon">📄</span>
      <span class="message-input__file-name">{{ selectedFile.name }}</span>
      <button class="message-input__file-remove" title="移除文件" @click="clearFile">
        <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor">
          <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" />
        </svg>
      </button>
    </div>

    <!-- Input area -->
    <div class="message-input__body">
      <div class="message-input__actions">
        <button class="message-input__action-btn" title="发送图片" @click="triggerImageUpload">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor">
            <path d="M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z" />
          </svg>
        </button>
        <button class="message-input__action-btn" title="发送文件" @click="triggerFileUpload">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor">
            <path d="M16.5 6v11.5c0 2.21-1.79 4-4 4s-4-1.79-4-4V5a2.5 2.5 0 0 1 5 0v10.5c0 .55-.45 1-1 1s-1-.45-1-1V6H10v9.5a2.5 2.5 0 0 0 5 0V5c0-2.21-1.79-4-4-4S7 2.79 7 5v12.5c0 3.04 2.46 5.5 5.5 5.5s5.5-2.46 5.5-5.5V6h-1.5z" />
          </svg>
        </button>
      </div>

      <textarea
        ref="textareaRef"
        v-model="textContent"
        class="message-input__textarea"
        placeholder="输入消息..."
        rows="3"
        @keydown="handleKeydown"
      />

      <button
        class="message-input__send-btn"
        :disabled="isSendDisabled"
        @click="handleSend"
      >
        发送
      </button>
    </div>

    <!-- Hidden file inputs -->
    <input
      ref="imageInputRef"
      type="file"
      accept="image/*"
      class="message-input__hidden-input"
      @change="handleImageSelect"
    />
    <input
      ref="fileInputRef"
      type="file"
      class="message-input__hidden-input"
      @change="handleFileSelect"
    />
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useChatStore } from '@/stores/chat.js'
import { MessageType } from '@/constants/messageType.js'

const props = defineProps({
  sessionId: {
    type: String,
    required: true
  }
})

const emit = defineEmits(['send'])

const chatStore = useChatStore()

const textContent = ref('')
const imagePreview = ref(null)
const selectedImage = ref(null)
const selectedFile = ref(null)
const textareaRef = ref(null)
const imageInputRef = ref(null)
const fileInputRef = ref(null)

const isSendDisabled = computed(() => {
  return textContent.value.trim() === '' && !selectedImage.value && !selectedFile.value
})

function handleKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

function handleSend() {
  if (isSendDisabled.value) return

  const quoteId = chatStore.quoteMessage ? chatStore.quoteMessage.id : undefined

  if (selectedImage.value) {
    emit('send', {
      type: MessageType.IMAGE,
      content: imagePreview.value,
      quoteId,
      file: selectedImage.value,
      fileMeta: {
        name: selectedImage.value.name,
        size: selectedImage.value.size,
        mimeType: selectedImage.value.type
      }
    })
    clearImage()
  } else if (selectedFile.value) {
    emit('send', {
      type: MessageType.FILE,
      content: selectedFile.value.name,
      quoteId,
      fileMeta: {
        name: selectedFile.value.name,
        size: selectedFile.value.size,
        mimeType: selectedFile.value.type
      }
    })
    clearFile()
  } else {
    emit('send', {
      type: MessageType.TEXT,
      content: textContent.value,
      quoteId
    })
  }

  textContent.value = ''
  chatStore.clearQuote()
  // Refocus textarea
  textareaRef.value?.focus()
}

function triggerImageUpload() {
  imageInputRef.value?.click()
}

function triggerFileUpload() {
  fileInputRef.value?.click()
}

function handleImageSelect(e) {
  const file = e.target.files?.[0]
  if (!file) return

  selectedImage.value = file
  // Clear any selected file
  selectedFile.value = null

  const reader = new FileReader()
  reader.onload = (ev) => {
    imagePreview.value = ev.target.result
  }
  reader.readAsDataURL(file)

  // Reset input so same file can be selected again
  e.target.value = ''
}

function handleFileSelect(e) {
  const file = e.target.files?.[0]
  if (!file) return

  selectedFile.value = file
  // Clear any selected image
  selectedImage.value = null
  imagePreview.value = null

  // Reset input
  e.target.value = ''
}

function clearImage() {
  selectedImage.value = null
  imagePreview.value = null
}

function clearFile() {
  selectedFile.value = null
}
</script>

<style lang="scss" scoped>
.message-input {
  border-top: 1px solid var(--border-light);
  background: var(--bg-white);

  // --- Quote preview ---
  &__quote {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: var(--spacing-sm) var(--spacing-md);
    background: var(--bg-hover);
    border-bottom: 1px solid var(--border-light);
  }

  &__quote-info {
    display: flex;
    align-items: center;
    gap: var(--spacing-xs);
    overflow: hidden;
    min-width: 0;
  }

  &__quote-label {
    font-size: var(--font-size-xs);
    color: var(--color-primary);
    font-weight: 500;
    flex-shrink: 0;
  }

  &__quote-sender {
    font-size: var(--font-size-sm);
    color: var(--text-link);
    font-weight: 500;
    flex-shrink: 0;

    &::after {
      content: '：';
    }
  }

  &__quote-content {
    font-size: var(--font-size-sm);
    color: var(--text-secondary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__quote-close {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 20px;
    height: 20px;
    border: none;
    background: transparent;
    color: var(--text-secondary);
    cursor: pointer;
    border-radius: var(--radius-sm);
    flex-shrink: 0;
    transition: background var(--transition-fast) ease, color var(--transition-fast) ease;

    &:hover {
      background: var(--bg-active);
      color: var(--text-primary);
    }
  }

  // --- Image preview ---
  &__image-preview {
    display: flex;
    align-items: center;
    gap: var(--spacing-sm);
    padding: var(--spacing-sm) var(--spacing-md);
    background: var(--bg-hover);
    border-bottom: 1px solid var(--border-light);
  }

  &__image-thumb {
    width: 60px;
    height: 60px;
    object-fit: cover;
    border-radius: var(--radius-sm);
    border: 1px solid var(--border-light);
  }

  &__image-remove {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 20px;
    height: 20px;
    border: none;
    background: transparent;
    color: var(--text-secondary);
    cursor: pointer;
    border-radius: var(--radius-sm);
    transition: background var(--transition-fast) ease, color var(--transition-fast) ease;

    &:hover {
      background: var(--bg-active);
      color: var(--text-primary);
    }
  }

  // --- File info ---
  &__file-info {
    display: flex;
    align-items: center;
    gap: var(--spacing-sm);
    padding: var(--spacing-sm) var(--spacing-md);
    background: var(--bg-hover);
    border-bottom: 1px solid var(--border-light);
  }

  &__file-icon {
    font-size: var(--font-size-lg);
    flex-shrink: 0;
  }

  &__file-name {
    font-size: var(--font-size-sm);
    color: var(--text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__file-remove {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 20px;
    height: 20px;
    border: none;
    background: transparent;
    color: var(--text-secondary);
    cursor: pointer;
    border-radius: var(--radius-sm);
    margin-left: auto;
    transition: background var(--transition-fast) ease, color var(--transition-fast) ease;

    &:hover {
      background: var(--bg-active);
      color: var(--text-primary);
    }
  }

  // --- Input body ---
  &__body {
    display: flex;
    align-items: flex-end;
    padding: var(--spacing-sm) var(--spacing-md);
    gap: var(--spacing-sm);
  }

  &__actions {
    display: flex;
    gap: var(--spacing-xs);
    flex-shrink: 0;
    padding-bottom: 2px;
  }

  &__action-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 32px;
    height: 32px;
    border: none;
    background: transparent;
    color: var(--text-secondary);
    cursor: pointer;
    border-radius: var(--radius-sm);
    transition: background var(--transition-fast) ease, color var(--transition-fast) ease;

    &:hover {
      background: var(--bg-hover);
      color: var(--text-primary);
    }
  }

  &__textarea {
    flex: 1;
    resize: none;
    border: 1px solid #e0e0e0;
    border-radius: 6px;
    padding: var(--spacing-sm) var(--spacing-md);
    font-size: var(--font-size-md);
    font-family: var(--font-family);
    line-height: var(--line-height-normal);
    color: var(--text-primary);
    background: #fff;
    outline: none;
    transition: border-color 200ms ease;

    &::placeholder {
      color: var(--text-placeholder);
    }

    &:focus {
      border-color: #bbb;
    }
  }

  &__send-btn {
    flex-shrink: 0;
    padding: var(--spacing-sm) var(--spacing-lg);
    border: none;
    border-radius: 6px;
    background: #07c160;
    color: #fff;
    font-size: var(--font-size-md);
    font-weight: 500;
    cursor: pointer;
    transition: background 150ms ease, opacity 150ms ease;

    &:hover:not(:disabled) {
      background: #06ad56;
    }

    &:active:not(:disabled) {
      background: #059a4c;
    }

    &:disabled {
      opacity: 0.4;
      cursor: not-allowed;
    }
  }

  // --- Hidden file inputs ---
  &__hidden-input {
    display: none;
  }
}
</style>
