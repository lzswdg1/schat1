<template>
  <Transition name="panel-slide">
    <div v-if="visible" class="group-detail-overlay" @click.self="$emit('close')">
      <div class="group-detail">
        <div class="group-detail__header">
          <span class="group-detail__title">{{ groupName }}</span>
          <button class="group-detail__close" aria-label="关闭" @click="$emit('close')">✕</button>
        </div>

        <div v-if="loading" class="group-detail__loading">加载中...</div>
        <template v-else>
          <!-- 群头像 -->
          <div class="group-detail__section group-detail__avatar-section">
            <div class="group-detail__section-title">群头像</div>
            <div class="group-detail__avatar-row">
              <div class="group-avatar-upload" @click="triggerAvatarUpload" :title="canEditAvatar ? '点击更换群头像' : ''">
                <UserAvatar :src="groupAvatarDisplay" :size="56" />
                <div v-if="canEditAvatar" class="group-avatar-upload__overlay">
                  <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor">
                    <path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a1 1 0 0 0 0-1.41l-2.34-2.34a1 1 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z"/>
                  </svg>
                </div>
              </div>
              <span v-if="uploadingAvatar" class="group-detail__avatar-status">上传中...</span>
            </div>
            <input
              ref="groupAvatarInputRef"
              type="file"
              accept="image/*"
              class="hidden-input"
              @change="handleGroupAvatarChange"
            />
          </div>

          <!-- 群名称（群主可编辑） -->
          <div class="group-detail__section group-detail__name-section">
            <div class="group-detail__section-title">群名称</div>
            <div class="group-detail__name-row">
              <template v-if="editingName">
                <input
                  v-model="nameText"
                  class="group-detail__name-input"
                  type="text"
                  maxlength="50"
                  placeholder="输入群名称"
                  @keydown.enter="saveName"
                />
                <button class="group-detail__btn group-detail__btn--small" @click="editingName = false">取消</button>
                <button class="group-detail__btn group-detail__btn--primary group-detail__btn--small" @click="saveName">保存</button>
              </template>
              <template v-else>
                <span class="group-detail__name-text">{{ displayGroupName }}</span>
                <button v-if="isOwner" class="group-detail__btn group-detail__btn--small" @click="startEditName">修改</button>
              </template>
            </div>
          </div>

        <div class="group-detail__section-title">
            群成员（{{ members.length }}）
          </div>
          <div class="group-detail__members">
            <div
              v-for="member in sortedMembers"
              :key="member.userId || member.id"
              class="member-item"
            >
              <UserAvatar :src="member.avatar" :size="32" />
              <span class="member-item__name">{{ member.nickname || member.userId || member.id }}</span>
              <span
                class="member-item__role"
                :class="`member-item__role--${member.role}`"
              >{{ roleLabel(member.role) }}</span>
              <button
                v-if="canKick(member)"
                class="member-item__kick"
                @click="confirmKick(member)"
              >踢出</button>
            </div>
          </div>

          <!-- 群主：转让群主 -->
          <div v-if="isOwner" class="group-detail__section">
            <div class="group-detail__section-title">转让群主</div>
            <div class="group-detail__transfer">
              <select v-model="transferTarget" class="group-detail__select">
                <option value="">选择新群主</option>
                <option
                  v-for="m in nonOwnerMembers"
                  :key="m.userId || m.id"
                  :value="m.userId || m.id"
                >{{ m.nickname || m.userId || m.id }}</option>
              </select>
              <button
                class="group-detail__btn group-detail__btn--warning"
                :disabled="!transferTarget || transferring"
                @click="confirmTransfer"
              >{{ transferring ? '转让中...' : '确认转让' }}</button>
            </div>
          </div>

          <!-- 群公告 -->
          <div class="group-detail__section">
            <div class="group-detail__section-title">群公告</div>
            <div v-if="editingNotice" class="group-detail__notice-edit">
              <textarea v-model="noticeText" class="group-detail__textarea" rows="3" placeholder="输入群公告..." />
              <div class="group-detail__notice-actions">
                <button class="group-detail__btn group-detail__btn--small" @click="editingNotice = false">取消</button>
                <button class="group-detail__btn group-detail__btn--primary group-detail__btn--small" @click="saveNotice">保存</button>
              </div>
            </div>
            <div v-else class="group-detail__notice-view">
              <p class="group-detail__notice-text">{{ groupNotice || '暂无公告' }}</p>
              <button v-if="isOwner || isAdmin" class="group-detail__btn group-detail__btn--small" @click="startEditNotice">编辑</button>
            </div>
          </div>

          <!-- 邀请成员 -->
          <div v-if="uninvitedFriends.length > 0" class="group-detail__section">
            <div class="group-detail__section-title">邀请好友入群</div>
            <div class="group-detail__invite-list">
              <label v-for="f in uninvitedFriends" :key="f.id" class="friend-check-item">
                <input type="checkbox" :value="f.id" v-model="inviteIds" class="friend-check-item__checkbox" />
                <UserAvatar :src="f.avatar" :size="24" />
                <span class="friend-check-item__name">{{ f.nickname }}</span>
              </label>
            </div>
            <button
              v-if="inviteIds.length > 0"
              class="group-detail__btn group-detail__btn--primary"
              :disabled="inviting"
              @click="doInvite"
            >{{ inviting ? '邀请中...' : `邀请 ${inviteIds.length} 人` }}</button>
          </div>

          <!-- 群主：解散群聊 -->
          <div v-if="isOwner" class="group-detail__section">
            <button
              class="group-detail__btn group-detail__btn--danger"
              :disabled="dismissing"
              @click="confirmDismiss"
            >{{ dismissing ? '解散中...' : '解散群聊' }}</button>
          </div>

          <!-- 非群主：退出群聊 -->
          <div v-if="!isOwner" class="group-detail__section">
            <button
              class="group-detail__btn group-detail__btn--danger"
              :disabled="leaving"
              @click="confirmLeave"
            >{{ leaving ? '退出中...' : '退出群聊' }}</button>
          </div>
        </template>

        <!-- 二次确认对话框 -->
        <Transition name="confirm-fade">
          <div v-if="confirmAction" class="confirm-dialog-overlay" @click.self="cancelConfirm">
            <div class="confirm-dialog">
              <p class="confirm-dialog__text">{{ confirmAction.message }}</p>
              <div class="confirm-dialog__actions">
                <button class="confirm-dialog__btn confirm-dialog__btn--cancel" @click="cancelConfirm">取消</button>
                <button
                  class="confirm-dialog__btn confirm-dialog__btn--confirm"
                  @click="executeConfirm"
                >确认</button>
              </div>
            </div>
          </div>
        </Transition>
      </div>
    </div>
  </Transition>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { useContactStore } from '@/stores/contact.js'
import { useSessionStore } from '@/stores/session.js'
import { useChatStore } from '@/stores/chat.js'
import { useAuthStore } from '@/stores/auth.js'
import { useNotificationStore } from '@/stores/notification.js'
import { uploadFile } from '@/api/chat.js'
import { normalizeFileUrl } from '@/api/http.js'
import UserAvatar from '@/components/common/UserAvatar.vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  groupId: { type: String, default: '' },
  groupName: { type: String, default: '' }
})

const emit = defineEmits(['close'])

const contactStore = useContactStore()
const sessionStore = useSessionStore()
const chatStore = useChatStore()
const authStore = useAuthStore()
const notificationStore = useNotificationStore()

const loading = ref(false)
const members = ref([])
const leaving = ref(false)
const dismissing = ref(false)
const transferring = ref(false)
const transferTarget = ref('')
const confirmAction = ref(null)
const editingNotice = ref(false)
const noticeText = ref('')
const groupNotice = ref('')
const inviteIds = ref([])
const inviting = ref(false)
const groupAvatarInputRef = ref(null)
const uploadingAvatar = ref(false)
const groupAvatarUrl = ref('')
const editingName = ref(false)
const nameText = ref('')
const displayGroupName = ref('')

const myId = computed(() => authStore.user?.id)
const myRole = computed(() => members.value.find(m => String(m.id || m.userId) === String(myId.value))?.role?.toLowerCase())
const isOwner = computed(() => myRole.value === 'owner')
const isAdmin = computed(() => myRole.value === 'admin')
const canEditAvatar = computed(() => isOwner.value || isAdmin.value)
const groupAvatarDisplay = computed(() => normalizeFileUrl(groupAvatarUrl.value))

const sortedMembers = computed(() => {
  const order = { OWNER: 0, ADMIN: 1, MEMBER: 2, owner: 0, admin: 1, member: 2 }
  return [...members.value].sort((a, b) => (order[a.role] ?? 3) - (order[b.role] ?? 3))
})

const nonOwnerMembers = computed(() => members.value.filter(m => String(m.id || m.userId) !== String(myId.value)))

const uninvitedFriends = computed(() => {
  const memberIdSet = new Set(members.value.map(m => String(m.userId || m.id)))
  return contactStore.friends.filter(f => !memberIdSet.has(String(f.id)))
})

function roleLabel(role) {
  const r = (role || '').toUpperCase()
  if (r === 'OWNER') return '群主'
  if (r === 'ADMIN') return '管理员'
  return '成员'
}

function canKick(member) {
  if (!isOwner.value && !isAdmin.value) return false
  const mid = String(member.userId || member.id)
  if (mid === String(myId.value)) return false
  const r = (member.role || '').toUpperCase()
  if (r === 'OWNER') return false
  if (r === 'ADMIN' && !isOwner.value) return false
  return true
}

watch(() => props.visible, async (val) => {
  if (val && props.groupId) {
    loading.value = true
    transferTarget.value = ''
    inviteIds.value = []
    editingNotice.value = false
    editingName.value = false
    try {
      delete contactStore.groupMembersCache[props.groupId]
      const res = await contactStore.fetchGroupMembers(props.groupId)
      if (res.code === 200) members.value = res.data
      // 加载群信息（获取公告）
      const infoRes = await contactStore.fetchGroupInfo(props.groupId)
      if (infoRes.code === 200 && infoRes.data) {
        groupNotice.value = infoRes.data.notice || ''
        groupAvatarUrl.value = infoRes.data.avatar || ''
        displayGroupName.value = infoRes.data.groupName || props.groupName || ''
      }
    } finally {
      loading.value = false
    }
  }
})

// ---- 二次确认 ----
function cancelConfirm() { confirmAction.value = null }

function executeConfirm() {
  if (confirmAction.value?.action) {
    confirmAction.value.action()
  }
  confirmAction.value = null
}

function confirmKick(member) {
  const name = member.nickname || member.userId || member.id
  confirmAction.value = {
    message: `确定要将「${name}」踢出群聊吗？`,
    action: () => doKick(member.userId || member.id)
  }
}

function confirmLeave() {
  confirmAction.value = {
    message: '确定要退出该群聊吗？退出后将无法查看群消息。',
    action: doLeave
  }
}

function confirmTransfer() {
  const target = members.value.find(m => String(m.userId || m.id) === String(transferTarget.value))
  if (!target) return
  const name = target.nickname || target.userId || target.id
  confirmAction.value = {
    message: `确定要将群主转让给「${name}」吗？转让后你将变为普通成员。`,
    action: doTransfer
  }
}

async function doKick(userId) {
  const res = await contactStore.kickMember(props.groupId, userId)
  if (res.code === 200) {
    members.value = members.value.filter(m => String(m.userId || m.id) !== String(userId))
    notificationStore.show('已踢出该成员', 'info')
  } else {
    notificationStore.show(res.message || '操作失败', 'error')
  }
}

async function doLeave() {
  leaving.value = true
  try {
    const res = await contactStore.leaveGroup(props.groupId)
    if (res.code === 200) {
      const removedId = sessionStore.removeSessionByTarget('group', props.groupId)
      if (removedId) await chatStore.removeSessionMessages(removedId)
      notificationStore.show('已退出群聊', 'info')
      emit('close')
    } else {
      notificationStore.show(res.message || '退群失败', 'error')
    }
  } finally {
    leaving.value = false
  }
}

function confirmDismiss() {
  confirmAction.value = {
    message: '确定要解散该群聊吗？所有消息和成员将被清除，此操作不可撤销。',
    action: doDismiss
  }
}

async function doDismiss() {
  dismissing.value = true
  try {
    const res = await contactStore.dismissGroup(props.groupId)
    if (res.code === 200) {
      const removedId = sessionStore.removeSessionByTarget('group', props.groupId)
      if (removedId) await chatStore.removeSessionMessages(removedId)
      notificationStore.show('群聊已解散', 'info')
      emit('close')
    } else {
      notificationStore.show(res.message || '解散失败', 'error')
    }
  } finally {
    dismissing.value = false
  }
}

function startEditNotice() {
  noticeText.value = groupNotice.value || ''
  editingNotice.value = true
}

function startEditName() {
  nameText.value = displayGroupName.value || ''
  editingName.value = true
}

async function saveName() {
  const name = nameText.value.trim()
  if (!name) {
    notificationStore.show('群名称不能为空', 'error')
    return
  }
  const res = await contactStore.renameGroup(props.groupId, name)
  if (res.code === 200) {
    displayGroupName.value = name
    editingName.value = false
    // 同步更新会话列表中的群名
    const session = sessionStore.sessions.find(s => s.id === `group_${props.groupId}`)
    if (session) session.targetName = name
    notificationStore.show('群名称已更新', 'info')
  } else {
    notificationStore.show(res.message || '更新失败', 'error')
  }
}

async function saveNotice() {
  const res = await contactStore.updateGroupNotice(props.groupId, noticeText.value)
  if (res.code === 200) {
    groupNotice.value = noticeText.value
    editingNotice.value = false
    notificationStore.show('公告已更新', 'info')
  } else {
    notificationStore.show(res.message || '更新失败', 'error')
  }
}

async function doInvite() {
  inviting.value = true
  try {
    const res = await contactStore.inviteGroupMembers(props.groupId, inviteIds.value)
    if (res.code === 200) {
      inviteIds.value = []
      // 刷新成员列表
      delete contactStore.groupMembersCache[props.groupId]
      const refreshRes = await contactStore.fetchGroupMembers(props.groupId)
      if (refreshRes.code === 200) members.value = refreshRes.data
      notificationStore.show('邀请成功', 'info')
    } else {
      notificationStore.show(res.message || '邀请失败', 'error')
    }
  } finally {
    inviting.value = false
  }
}

function triggerAvatarUpload() {
  if (!canEditAvatar.value) return
  groupAvatarInputRef.value?.click()
}

async function handleGroupAvatarChange(e) {
  const file = e.target.files?.[0]
  if (!file) return
  e.target.value = ''

  uploadingAvatar.value = true
  try {
    const uploadRes = await uploadFile(file)
    const rawUrl = uploadRes?.code === 200
      ? (typeof uploadRes.data === 'string' ? uploadRes.data : uploadRes.data?.url)
      : null
    if (!rawUrl) {
      notificationStore.show('图片上传失败', 'error')
      return
    }

    const res = await contactStore.updateGroupAvatar(props.groupId, rawUrl)
    if (res.code === 200) {
      groupAvatarUrl.value = rawUrl
      // 同步更新会话列表中的群头像
      const session = sessionStore.sessions.find(s => s.id === `group_${props.groupId}`)
      if (session) session.targetAvatar = normalizeFileUrl(rawUrl)
      notificationStore.show('群头像已更新', 'info')
    } else {
      notificationStore.show(res.message || '更新失败', 'error')
    }
  } catch {
    notificationStore.show('群头像上传失败', 'error')
  } finally {
    uploadingAvatar.value = false
  }
}

async function doTransfer() {
  if (!transferTarget.value) return
  transferring.value = true
  try {
    const res = await contactStore.transferOwner(props.groupId, transferTarget.value)
    if (res.code === 200) {
      // 刷新成员列表
      delete contactStore.groupMembersCache[props.groupId]
      const refreshRes = await contactStore.fetchGroupMembers(props.groupId)
      if (refreshRes.code === 200) members.value = refreshRes.data
      transferTarget.value = ''
      notificationStore.show('群主已转让', 'info')
    } else {
      notificationStore.show(res.message || '转让失败', 'error')
    }
  } finally {
    transferring.value = false
  }
}
</script>

<style lang="scss" scoped>
.group-detail-overlay {
  position: fixed;
  inset: 0;
  z-index: var(--z-modal);
  display: flex;
  justify-content: flex-end;
  background: rgba(0, 0, 0, 0.3);
}

.group-detail {
  width: 320px;
  height: 100%;
  background: var(--bg-white);
  box-shadow: var(--shadow-lg);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  position: relative;

  &__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: var(--spacing-md) var(--spacing-lg);
    border-bottom: 1px solid var(--border-light);
    flex-shrink: 0;
  }

  &__title {
    font-size: var(--font-size-lg);
    font-weight: 500;
    color: var(--text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__close {
    border: none;
    background: transparent;
    font-size: 16px;
    color: var(--text-secondary);
    cursor: pointer;
    padding: 4px;
    border-radius: var(--radius-sm);
    &:hover { background: var(--bg-hover); color: var(--text-primary); }
  }

  &__loading {
    padding: var(--spacing-xl);
    text-align: center;
    color: var(--text-secondary);
    font-size: var(--font-size-sm);
  }

  &__section-title {
    padding: var(--spacing-sm) var(--spacing-lg);
    font-size: var(--font-size-sm);
    color: var(--text-secondary);
    font-weight: 500;
  }

  &__members {
    flex: 1;
    overflow-y: auto;
    padding: 0 var(--spacing-sm);
  }

  &__section {
    padding: var(--spacing-md) var(--spacing-lg);
    border-top: 1px solid var(--border-light);
  }

  &__transfer {
    display: flex;
    gap: var(--spacing-sm);
    margin-top: var(--spacing-sm);
  }

  &__select {
    flex: 1;
    padding: 6px 10px;
    font-size: var(--font-size-sm);
    border: 1px solid var(--border-input);
    border-radius: var(--radius-sm);
    background: var(--bg-white);
    color: var(--text-primary);
    outline: none;
    &:focus { border-color: var(--color-primary); }
  }

  &__btn {
    padding: 8px 16px;
    font-size: var(--font-size-sm);
    font-weight: 500;
    border: none;
    border-radius: var(--radius-md);
    cursor: pointer;
    transition: opacity 150ms, transform 150ms;
    &:disabled { opacity: 0.4; cursor: not-allowed; }
    &:active:not(:disabled) { transform: scale(0.97); }

    &--danger {
      width: 100%;
      color: #fff;
      background: var(--text-danger);
    }

    &--warning {
      color: #fff;
      background: #faad14;
      flex-shrink: 0;
    }

    &--primary {
      width: 100%;
      color: #fff;
      background: var(--color-primary);
    }

    &--small {
      width: auto;
      padding: 4px 12px;
      font-size: 12px;
    }
  }

  &__notice-edit {
    padding: 0 var(--spacing-lg) var(--spacing-sm);
  }

  &__textarea {
    width: 100%;
    padding: 8px;
    font-size: var(--font-size-sm);
    border: 1px solid var(--border-input);
    border-radius: var(--radius-sm);
    resize: none;
    outline: none;
    box-sizing: border-box;
    &:focus { border-color: var(--color-primary); }
  }

  &__notice-actions {
    display: flex;
    gap: var(--spacing-xs);
    justify-content: flex-end;
    margin-top: var(--spacing-xs);
  }

  &__notice-view {
    padding: 0 var(--spacing-lg) var(--spacing-sm);
    display: flex;
    align-items: flex-start;
    gap: var(--spacing-sm);
  }

  &__notice-text {
    flex: 1;
    margin: 0;
    font-size: var(--font-size-sm);
    color: var(--text-secondary);
    line-height: 1.5;
  }

  &__invite-list {
    max-height: 150px;
    overflow-y: auto;
    padding: 0 var(--spacing-lg);
    margin-bottom: var(--spacing-sm);
  }

  &__avatar-section {
    border-top: none;
  }

  &__name-section {
    border-top: none;
    padding-top: 0;
  }

  &__name-row {
    display: flex;
    align-items: center;
    gap: var(--spacing-sm);
    padding: 0 var(--spacing-lg) var(--spacing-sm);
  }

  &__name-text {
    flex: 1;
    font-size: var(--font-size-md);
    color: var(--text-primary);
    font-weight: 500;
  }

  &__name-input {
    flex: 1;
    padding: 6px 10px;
    font-size: var(--font-size-sm);
    border: 1px solid var(--border-input);
    border-radius: var(--radius-sm);
    background: var(--bg-white);
    color: var(--text-primary);
    outline: none;
    box-sizing: border-box;
    &:focus { border-color: var(--color-primary); }
  }

  &__avatar-row {
    display: flex;
    align-items: center;
    gap: var(--spacing-md);
    padding: 0 var(--spacing-lg) var(--spacing-sm);
  }

  &__avatar-status {
    font-size: var(--font-size-sm);
    color: var(--text-secondary);
  }
}

.group-avatar-upload {
  position: relative;
  cursor: pointer;
  border-radius: var(--radius-round);
  flex-shrink: 0;

  .group-avatar-upload__overlay {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    background: rgba(0, 0, 0, 0.4);
    border-radius: var(--radius-round);
    color: white;
    opacity: 0;
    transition: opacity 200ms ease;
  }

  &:hover .group-avatar-upload__overlay {
    opacity: 1;
  }
}

.hidden-input {
  display: none;
}

.member-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: 6px var(--spacing-sm);
  border-radius: var(--radius-sm);
  transition: background 150ms;
  &:hover { background: var(--bg-hover); }

  &__name {
    flex: 1;
    font-size: var(--font-size-md);
    color: var(--text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__role {
    font-size: 11px;
    padding: 1px 6px;
    border-radius: 8px;
    flex-shrink: 0;

    &--owner {
      background: rgba(250, 173, 20, 0.15);
      color: #d48806;
    }
    &--admin {
      background: rgba(7, 193, 96, 0.1);
      color: var(--color-primary-dark);
    }
    &--member {
      background: rgba(0, 0, 0, 0.04);
      color: var(--text-secondary);
    }
  }

  &__kick {
    font-size: 11px;
    padding: 2px 8px;
    border: 1px solid var(--text-danger);
    color: var(--text-danger);
    background: transparent;
    border-radius: var(--radius-sm);
    cursor: pointer;
    flex-shrink: 0;
    transition: background 150ms, color 150ms;
    &:hover { background: var(--text-danger); color: #fff; }
  }
}

// ---- 二次确认对话框 ----
.confirm-dialog-overlay {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10;
}

.confirm-dialog {
  background: var(--bg-white);
  border-radius: var(--radius-lg);
  padding: var(--spacing-xl);
  width: 260px;
  box-shadow: var(--shadow-lg);

  &__text {
    margin: 0 0 var(--spacing-lg);
    font-size: var(--font-size-md);
    color: var(--text-primary);
    line-height: 1.5;
  }

  &__actions {
    display: flex;
    gap: var(--spacing-sm);
    justify-content: flex-end;
  }

  &__btn {
    padding: 6px 16px;
    font-size: var(--font-size-sm);
    border-radius: var(--radius-sm);
    border: none;
    cursor: pointer;
    font-weight: 500;
    transition: opacity 150ms;

    &--cancel {
      background: var(--bg-hover);
      color: var(--text-secondary);
    }
    &--confirm {
      background: var(--text-danger);
      color: #fff;
    }
  }
}

// ---- Transitions ----
.panel-slide-enter-active {
  transition: opacity 250ms ease, transform 250ms var(--spring);
  .group-detail { transition: transform 250ms var(--spring); }
}
.panel-slide-leave-active {
  transition: opacity 200ms ease;
  .group-detail { transition: transform 200ms ease; }
}
.panel-slide-enter-from {
  opacity: 0;
  .group-detail { transform: translateX(100%); }
}
.panel-slide-leave-to {
  opacity: 0;
  .group-detail { transform: translateX(100%); }
}

.confirm-fade-enter-active,
.confirm-fade-leave-active {
  transition: opacity 150ms ease;
}
.confirm-fade-enter-from,
.confirm-fade-leave-to {
  opacity: 0;
}

.friend-check-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: 4px var(--spacing-xs);
  border-radius: var(--radius-sm);
  cursor: pointer;
  &:hover { background: var(--bg-hover); }

  &__checkbox {
    accent-color: var(--color-primary);
    width: 14px;
    height: 14px;
    cursor: pointer;
  }

  &__name {
    font-size: var(--font-size-sm);
    color: var(--text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}
</style>
