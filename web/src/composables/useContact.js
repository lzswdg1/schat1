/**
 * 联系人操作组合函数
 */

import { ref } from 'vue'
import { useContactStore } from '@/stores/contact.js'
import { useSessionStore } from '@/stores/session.js'
import { useChatStore } from '@/stores/chat.js'

/**
 * 好友搜索 + 发起申请
 */
export function useFriendSearch() {
  const contactStore = useContactStore()
  const sessionStore = useSessionStore()

  const searchLoading = ref(false)
  const addLoading = ref(false)
  const searched = ref(false)

  async function search(userId) {
    if (!userId || !userId.trim()) return
    searchLoading.value = true
    searched.value = false
    contactStore.searchResult = null
    try {
      await contactStore.searchUser(userId.trim())
      searched.value = true
    } finally {
      searchLoading.value = false
    }
  }

  async function sendRequest(userId) {
    addLoading.value = true
    try {
      const res = await contactStore.sendFriendRequest(userId)
      if (res.code === 200) {
        contactStore.searchResult = null
        searched.value = false
      }
      return res
    } finally {
      addLoading.value = false
    }
  }

  // 兼容旧接口名
  async function addFriend(userId) {
    return sendRequest(userId)
  }

  function reset() {
    contactStore.searchResult = null
    searched.value = false
  }

  return {
    searchResult: contactStore.$state,
    searchLoading,
    addLoading,
    searched,
    search,
    addFriend,
    sendRequest,
    reset
  }
}

/**
 * 好友列表 + 删除
 */
export function useFriendList() {
  const contactStore = useContactStore()
  const sessionStore = useSessionStore()
  const chatStore = useChatStore()
  const loading = ref(false)
  const deleteLoading = ref(false)

  async function loadFriends() {
    loading.value = true
    try {
      await contactStore.fetchFriends()
    } finally {
      loading.value = false
    }
  }

  async function deleteFriend(friendId) {
    deleteLoading.value = true
    try {
      const res = await contactStore.removeFriend(friendId)
      if (res.code === 200) {
        // 删除好友后清除本地聊天会话
        const removedSessionId = sessionStore.removeSessionByTarget('private', friendId)
        if (removedSessionId) {
          chatStore.removeSessionMessages(removedSessionId)
        }
      }
      return res
    } finally {
      deleteLoading.value = false
    }
  }

  return {
    loading,
    deleteLoading,
    loadFriends,
    deleteFriend
  }
}
