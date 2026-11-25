/**
 * API 统一导出
 */

export { login, register, logout } from './auth.js'
export {
  getFriends, searchUser, addFriend, removeFriend,
  sendFriendRequest, getFriendRequests, acceptFriendRequest, rejectFriendRequest,
  getGroups, getGroupMembers, getGroupInfo, createGroup,
  leaveGroup, kickMember, transferOwner, dismissGroup,
  updateGroupNotice, renameGroup, setGroupAdmin, inviteGroupMembers,
  updateGroupAvatar
} from './contact.js'
export { getHistory, sendMessage, recallMessage, sendReadReceipt, markGroupRead, uploadFile } from './chat.js'
export { default as http } from './http.js'
export { wsManager, initWebSocket } from './websocket.js'
