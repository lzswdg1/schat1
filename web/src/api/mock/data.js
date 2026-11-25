/**
 * Mock 数据存储
 * 首次加载时初始化预设数据，运行时数据写入 localStorage 持久化
 */

import { MessageType } from '@/constants/messageType.js'

const STORAGE_KEY = 'im_mock_db'
const DB_VERSION = 4 // 递增版本号强制刷新旧数据

// ============ 预设用户数据 ============

const defaultUsers = [
  { id: 'user_1', username: 'zhangsan', nickname: '张三', avatar: 'https://api.dicebear.com/7.x/avataaars/svg?seed=zhangsan' },
  { id: 'user_2', username: 'lisi', nickname: '李四', avatar: 'https://api.dicebear.com/7.x/avataaars/svg?seed=lisi' },
  { id: 'user_3', username: 'wangwu', nickname: '王五', avatar: 'https://api.dicebear.com/7.x/avataaars/svg?seed=wangwu' },
  { id: 'user_4', username: 'zhaoliu', nickname: '赵六', avatar: 'https://api.dicebear.com/7.x/avataaars/svg?seed=zhaoliu' },
  { id: 'user_5', username: 'sunqi', nickname: '孙七', avatar: 'https://api.dicebear.com/7.x/avataaars/svg?seed=sunqi' },
  { id: 'user_6', username: 'zhouba', nickname: '周八', avatar: 'https://api.dicebear.com/7.x/avataaars/svg?seed=zhouba' }
]

// user_1 的好友：user_2, user_3, user_4, user_5
const defaultFriends = {
  user_1: ['user_2', 'user_3', 'user_4', 'user_5'],
  user_2: ['user_1', 'user_3'],
  user_3: ['user_1', 'user_2', 'user_5'],
  user_4: ['user_1'],
  user_5: ['user_1', 'user_3'],
  user_6: []
}

const defaultGroups = [
  {
    id: 'group_1',
    name: '项目讨论组',
    avatar: 'https://api.dicebear.com/7.x/identicon/svg?seed=project',
    members: ['user_1', 'user_2', 'user_3', 'user_4'],
    roles: { user_1: 'owner', user_2: 'admin', user_3: 'member', user_4: 'member' }
  },
  {
    id: 'group_2',
    name: '周末活动群',
    avatar: 'https://api.dicebear.com/7.x/identicon/svg?seed=weekend',
    members: ['user_1', 'user_3', 'user_5', 'user_6'],
    roles: { user_1: 'owner', user_3: 'member', user_5: 'member', user_6: 'member' }
  }
]

// ============ 预设会话数据 ============

const now = Date.now()

const defaultSessions = {
  user_1: [
    {
      id: 'session_1_2',
      type: 'private',
      targetId: 'user_2',
      targetName: '李四',
      targetAvatar: 'https://api.dicebear.com/7.x/avataaars/svg?seed=lisi',
      lastMessage: '明天的会议几点开始？',
      lastTime: now - 60000,
      unreadCount: 1
    },
    {
      id: 'session_1_3',
      type: 'private',
      targetId: 'user_3',
      targetName: '王五',
      targetAvatar: 'https://api.dicebear.com/7.x/avataaars/svg?seed=wangwu',
      lastMessage: '好的，收到',
      lastTime: now - 3600000,
      unreadCount: 0
    },
    {
      id: 'session_1_4',
      type: 'private',
      targetId: 'user_4',
      targetName: '赵六',
      targetAvatar: 'https://api.dicebear.com/7.x/avataaars/svg?seed=zhaoliu',
      lastMessage: '文件已发送',
      lastTime: now - 7200000,
      unreadCount: 2
    },
    {
      id: 'session_g1',
      type: 'group',
      targetId: 'group_1',
      targetName: '项目讨论组',
      targetAvatar: 'https://api.dicebear.com/7.x/identicon/svg?seed=project',
      lastMessage: '王五: 代码已提交',
      lastTime: now - 1800000,
      unreadCount: 3
    },
    {
      id: 'session_g2',
      type: 'group',
      targetId: 'group_2',
      targetName: '周末活动群',
      targetAvatar: 'https://api.dicebear.com/7.x/identicon/svg?seed=weekend',
      lastMessage: '孙七: 周六下午可以',
      lastTime: now - 86400000,
      unreadCount: 0
    }
  ]
}

// ============ 预设消息数据 ============

const userMap = {}
defaultUsers.forEach(u => { userMap[u.id] = u })

function msg(id, sessionId, senderId, content, timeOffset, type = MessageType.TEXT, extra = {}) {
  const sender = userMap[senderId]
  return {
    id,
    sessionId,
    type,
    senderId,
    senderName: sender.nickname,
    senderAvatar: sender.avatar,
    content,
    timestamp: now - timeOffset,
    recalled: false,
    quote: extra.quote || null,
    fileMeta: extra.fileMeta || null
  }
}

const defaultMessages = {
  session_1_2: [
    msg('msg_1_2_1', 'session_1_2', 'user_1', '你好，李四！', 600000),
    msg('msg_1_2_2', 'session_1_2', 'user_2', '你好，张三！最近怎么样？', 540000),
    msg('msg_1_2_3', 'session_1_2', 'user_1', '还不错，在忙项目', 480000),
    msg('msg_1_2_4', 'session_1_2', 'user_2', '明天的会议几点开始？', 60000)
  ],
  session_1_3: [
    msg('msg_1_3_1', 'session_1_3', 'user_3', '张三，方案发你了', 7200000),
    msg('msg_1_3_2', 'session_1_3', 'user_1', '好的，收到', 3600000)
  ],
  session_1_4: [
    msg('msg_1_4_1', 'session_1_4', 'user_1', '赵六，帮我看下这个文件', 14400000),
    msg('msg_1_4_2', 'session_1_4', 'user_4', '没问题，稍等', 10800000),
    msg('msg_1_4_3', 'session_1_4', 'user_4', '文件已发送', 7200000, MessageType.FILE, {
      fileMeta: { name: '需求文档.pdf', size: 2048576, mimeType: 'application/pdf' }
    })
  ],
  session_g1: [
    msg('msg_g1_1', 'session_g1', 'user_2', '大家今天的任务分配一下', 86400000),
    msg('msg_g1_2', 'session_g1', 'user_1', '我负责前端部分', 82800000),
    msg('msg_g1_3', 'session_g1', 'user_3', '我来写后端接口', 79200000),
    msg('msg_g1_4', 'session_g1', 'user_4', '测试我来', 75600000),
    msg('msg_g1_5', 'session_g1', 'user_3', '代码已提交', 1800000)
  ],
  session_g2: [
    msg('msg_g2_1', 'session_g2', 'user_1', '周末有人想出去玩吗？', 172800000),
    msg('msg_g2_2', 'session_g2', 'user_3', '可以啊，去哪里？', 169200000),
    msg('msg_g2_3', 'session_g2', 'user_5', '周六下午可以', 86400000)
  ]
}

// ============ 预设密码数据（仅 Mock 用） ============

const defaultPasswords = {
  zhangsan: '123456',
  lisi: '123456',
  wangwu: '123456',
  zhaoliu: '123456',
  sunqi: '123456',
  zhouba: '123456'
}

// ============ 序列化 / 反序列化工具 ============

/**
 * 将 mockDB 序列化为可存储的 JSON 对象
 */
function serialize(db) {
  return {
    users: Object.fromEntries(db.users),
    friends: Object.fromEntries(db.friends),
    groups: Object.fromEntries(db.groups),
    sessions: Object.fromEntries(db.sessions),
    messages: Object.fromEntries(db.messages),
    tokens: Object.fromEntries(db.tokens),
    passwords: Object.fromEntries(db.passwords),
    friendRequests: Object.fromEntries(db.friendRequests)
  }
}

/**
 * 从 JSON 对象反序列化为 mockDB 结构
 */
function deserialize(raw) {
  return {
    users: new Map(Object.entries(raw.users || {})),
    friends: new Map(Object.entries(raw.friends || {})),
    groups: new Map(Object.entries(raw.groups || {})),
    sessions: new Map(Object.entries(raw.sessions || {})),
    messages: new Map(Object.entries(raw.messages || {})),
    tokens: new Map(Object.entries(raw.tokens || {})),
    passwords: new Map(Object.entries(raw.passwords || {})),
    friendRequests: new Map(Object.entries(raw.friendRequests || {}))
  }
}

// ============ 初始化逻辑 ============

/**
 * 构建默认的 mockDB
 */
function buildDefaultDB() {
  const db = {
    users: new Map(),
    friends: new Map(),
    groups: new Map(),
    sessions: new Map(),
    messages: new Map(),
    tokens: new Map(),
    passwords: new Map(),
    friendRequests: new Map()
  }

  // 用户
  defaultUsers.forEach(u => db.users.set(u.id, u))

  // 好友关系
  Object.entries(defaultFriends).forEach(([uid, fids]) => {
    db.friends.set(uid, [...fids])
  })

  // 群组
  defaultGroups.forEach(g => db.groups.set(g.id, g))

  // 会话
  Object.entries(defaultSessions).forEach(([uid, sessions]) => {
    db.sessions.set(uid, [...sessions])
  })

  // 消息
  Object.entries(defaultMessages).forEach(([sid, msgs]) => {
    db.messages.set(sid, [...msgs])
  })

  // 密码
  Object.entries(defaultPasswords).forEach(([username, pwd]) => {
    db.passwords.set(username, pwd)
  })

  return db
}

/**
 * 从 localStorage 加载数据，若不存在则初始化默认数据
 */
function loadDB() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    const ver = localStorage.getItem(STORAGE_KEY + '_ver')
    if (raw && Number(ver) === DB_VERSION) {
      return deserialize(JSON.parse(raw))
    }
  } catch (e) {
    console.warn('[MockDB] Failed to load from localStorage, using defaults:', e)
  }
  // 版本不匹配或无数据，清除旧数据并重建
  localStorage.removeItem(STORAGE_KEY)
  localStorage.setItem(STORAGE_KEY + '_ver', String(DB_VERSION))
  return buildDefaultDB()
}

// ============ 导出 mockDB 实例 ============

export const mockDB = loadDB()

/**
 * 将当前 mockDB 状态持久化到 localStorage
 * 在每次数据变更后调用
 */
export function persistDB() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(serialize(mockDB)))
  } catch (e) {
    console.warn('[MockDB] Failed to persist to localStorage:', e)
  }
}

/**
 * 重置 mockDB 为默认数据（用于测试或重置场景）
 */
export function resetDB() {
  const defaultDB = buildDefaultDB()
  mockDB.users = defaultDB.users
  mockDB.friends = defaultDB.friends
  mockDB.groups = defaultDB.groups
  mockDB.sessions = defaultDB.sessions
  mockDB.messages = defaultDB.messages
  mockDB.tokens = defaultDB.tokens
  mockDB.passwords = defaultDB.passwords
  mockDB.friendRequests = defaultDB.friendRequests
  persistDB()
}
