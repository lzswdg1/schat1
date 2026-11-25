package com.zw.sserver.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zw.sserver.mapper.GroupInfoMapper;
import com.zw.sserver.mapper.GroupMemberMapper;
import com.zw.sserver.mapper.GroupMessageMapper;
import com.zw.sserver.model.entity.GroupInfo;
import com.zw.sserver.model.entity.GroupMember;
import com.zw.sserver.model.entity.GroupMessage;
import com.zw.sserver.model.vo.GroupHistoryVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 群组管理服务
 */
@Slf4j
@Service
public class GroupService {

    private static final int MAX_GROUP_MEMBERS = 1000;

    private final GroupInfoMapper groupInfoMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final GroupMessageMapper groupMessageMapper;
    private final GroupMemberCacheService groupMemberCacheService;
    private final GroupReadCursorService groupReadCursorService;
    private final com.zw.sserver.infrastructure.message.JdbcMessageOutbox outbox;

    public GroupService(GroupInfoMapper groupInfoMapper,
                        GroupMemberMapper groupMemberMapper,
                        GroupMessageMapper groupMessageMapper,
                        GroupMemberCacheService groupMemberCacheService,
                        GroupReadCursorService groupReadCursorService,
                        com.zw.sserver.infrastructure.message.JdbcMessageOutbox outbox) {
        this.groupInfoMapper = groupInfoMapper;
        this.groupMemberMapper = groupMemberMapper;
        this.groupMessageMapper = groupMessageMapper;
        this.groupMemberCacheService = groupMemberCacheService;
        this.groupReadCursorService = groupReadCursorService;
        this.outbox = outbox;
    }

    /**
     * 创建群聊
     */
    @Transactional
    public GroupInfo createGroup(Long ownerId, String groupName, List<Long> initialMemberIds) {
        GroupInfo group = new GroupInfo();
        group.setGroupName(groupName);
        group.setOwnerId(ownerId);
        group.setMaxMembers(MAX_GROUP_MEMBERS);
        group.setMemberCount(1);
        group.setCreateTime(new Date());
        group.setUpdateTime(new Date());
        groupInfoMapper.insert(group);

        // 群主自动加入
        addMemberInternal(group.getGroupId(), ownerId, "OWNER");

        // 添加初始成员（先去重，防止 memberCount 不准）
        if (initialMemberIds != null) {
            List<Long> distinctMembers = initialMemberIds.stream()
                    .filter(Objects::nonNull)
                    .filter(id -> !id.equals(ownerId))
                    .distinct()
                    .limit(MAX_GROUP_MEMBERS - 1L)
                    .collect(Collectors.toList());
            for (Long memberId : distinctMembers) {
                addMemberInternal(group.getGroupId(), memberId, "MEMBER");
            }
            group.setMemberCount(1 + distinctMembers.size());
            groupInfoMapper.updateById(group);
        }

        return group;
    }

    /**
     * 加入群聊
     */
    @Transactional
    public boolean joinGroup(Long groupId, Long userId) {
        GroupInfo group = groupInfoMapper.selectForUpdate(groupId);
        if (group == null) return false;
        if (getGroupMember(groupId, userId) != null) return true;
        if (group.getMemberCount() >= group.getMaxMembers()) return false;

        addMemberInternal(groupId, userId, "MEMBER");

        group.setMemberCount(group.getMemberCount() + 1);
        group.setUpdateTime(new Date());
        groupInfoMapper.updateById(group);
        groupMemberCacheService.evict(groupId);

        return true;
    }

    /**
     * 退出群聊
     * 群主不能退出（需要先转让群主）
     */
    @Transactional
    public boolean leaveGroup(Long groupId, Long userId) {
        GroupInfo group = groupInfoMapper.selectForUpdate(groupId);
        if (group == null) return false;

        // 群主不能退出
        if (userId.equals(group.getOwnerId())) {
            throw new RuntimeException("群主不能退出群聊，请先转让群主或解散群聊");
        }

        LambdaQueryWrapper<GroupMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GroupMember::getGroupId, groupId).eq(GroupMember::getUserId, userId);
        int deleted = groupMemberMapper.delete(wrapper);

        if (deleted > 0) {
            group.setMemberCount(Math.max(0, group.getMemberCount() - 1));
            group.setUpdateTime(new Date());
            groupInfoMapper.updateById(group);
            groupMemberCacheService.evict(groupId);
        }

        return deleted > 0;
    }

    /**
     * 踢出群成员（仅 OWNER/ADMIN 可操作）
     */
    @Transactional
    public void kickMember(Long groupId, Long operatorId, Long targetUserId) {
        GroupInfo group = groupInfoMapper.selectForUpdate(groupId);
        if (group == null) throw new RuntimeException("群不存在");

        // 不能踢自己
        if (operatorId.equals(targetUserId)) throw new RuntimeException("不能踢出自己");

        // 查操作者角色
        GroupMember operator = getGroupMember(groupId, operatorId);
        if (operator == null) throw new RuntimeException("你不是该群成员");

        // 只有 OWNER 和 ADMIN 可以踢人
        if (!"OWNER".equals(operator.getRole()) && !"ADMIN".equals(operator.getRole())) {
            throw new RuntimeException("无权限操作");
        }

        // 查目标用户角色
        GroupMember target = getGroupMember(groupId, targetUserId);
        if (target == null) throw new RuntimeException("目标用户不在群中");

        // 不能踢群主
        if ("OWNER".equals(target.getRole())) throw new RuntimeException("不能踢出群主");

        // ADMIN 不能踢 ADMIN（只有 OWNER 可以）
        if ("ADMIN".equals(target.getRole()) && !"OWNER".equals(operator.getRole())) {
            throw new RuntimeException("管理员不能踢出其他管理员");
        }

        // 执行踢出
        LambdaQueryWrapper<GroupMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GroupMember::getGroupId, groupId).eq(GroupMember::getUserId, targetUserId);
        groupMemberMapper.delete(wrapper);

        group.setMemberCount(Math.max(0, group.getMemberCount() - 1));
        group.setUpdateTime(new Date());
        groupInfoMapper.updateById(group);
        groupMemberCacheService.evict(groupId);
    }

    /**
     * 转让群主（仅 OWNER 可操作）
     */
    @Transactional
    public void transferOwner(Long groupId, Long currentOwnerId, Long newOwnerId) {
        GroupInfo group = groupInfoMapper.selectForUpdate(groupId);
        if (group == null) throw new RuntimeException("群不存在");
        if (!currentOwnerId.equals(group.getOwnerId())) throw new RuntimeException("只有群主可以转让");

        GroupMember newOwnerMember = getGroupMember(groupId, newOwnerId);
        if (newOwnerMember == null) throw new RuntimeException("目标用户不在群中");

        // 旧群主 → MEMBER
        GroupMember oldOwnerMember = getGroupMember(groupId, currentOwnerId);
        oldOwnerMember.setRole("MEMBER");
        groupMemberMapper.updateById(oldOwnerMember);

        // 新群主 → OWNER
        newOwnerMember.setRole("OWNER");
        groupMemberMapper.updateById(newOwnerMember);

        // 更新群信息
        group.setOwnerId(newOwnerId);
        group.setUpdateTime(new Date());
        groupInfoMapper.updateById(group);

        // 刷新缓存
        groupMemberCacheService.evict(groupId);
    }



    /**
     * 解散群聊（仅群主）
     * 删除所有成员、群消息、群信息，返回被解散前的成员 ID 列表（用于通知）
     */
    @Transactional
    public List<Long> dismissGroup(Long groupId, Long ownerId) {
        GroupInfo group = groupInfoMapper.selectForUpdate(groupId);
        if (group == null || !ownerId.equals(group.getOwnerId())) return null;

        // 获取所有成员 ID（通知用）
        List<Long> memberIds = groupMemberMapper.selectMemberIds(groupId);

        outbox.cancelGroup(groupId);

        // 删除群消息
        LambdaQueryWrapper<GroupMessage> msgWrapper = new LambdaQueryWrapper<>();
        msgWrapper.eq(GroupMessage::getGroupId, groupId);
        groupMessageMapper.delete(msgWrapper);

        // 删除所有成员
        LambdaQueryWrapper<GroupMember> memberWrapper = new LambdaQueryWrapper<>();
        memberWrapper.eq(GroupMember::getGroupId, groupId);
        groupMemberMapper.delete(memberWrapper);

        // 删除群信息
        groupInfoMapper.deleteById(groupId);

        // 清除缓存
        groupMemberCacheService.evict(groupId);

        return memberIds;
    }

    /**
     * 修改群头像（仅 OWNER/ADMIN）
     */
    @Transactional
    public void updateAvatar(Long groupId, Long operatorId, String avatar) {
        GroupInfo group = groupInfoMapper.selectById(groupId);
        if (group == null) throw new RuntimeException("群不存在");

        GroupMember operator = getGroupMember(groupId, operatorId);
        if (operator == null) throw new RuntimeException("你不是该群成员");
        if (!"OWNER".equals(operator.getRole()) && !"ADMIN".equals(operator.getRole())) {
            throw new RuntimeException("无权限操作");
        }

        group.setAvatar(avatar);
        group.setUpdateTime(new Date());
        groupInfoMapper.updateById(group);
    }

    /**
     * 修改群公告（仅 OWNER/ADMIN）
     */
    @Transactional
    public void updateNotice(Long groupId, Long operatorId, String notice) {
        GroupInfo group = groupInfoMapper.selectById(groupId);
        if (group == null) throw new RuntimeException("群不存在");

        GroupMember operator = getGroupMember(groupId, operatorId);
        if (operator == null) throw new RuntimeException("你不是该群成员");
        if (!"OWNER".equals(operator.getRole()) && !"ADMIN".equals(operator.getRole())) {
            throw new RuntimeException("无权限操作");
        }

        group.setNotice(notice);
        group.setUpdateTime(new Date());
        groupInfoMapper.updateById(group);
    }

    /**
     * 修改群名称（仅 OWNER/ADMIN）
     */
    @Transactional
    public void updateGroupName(Long groupId, Long operatorId, String groupName) {
        GroupInfo group = groupInfoMapper.selectById(groupId);
        if (group == null) throw new RuntimeException("群不存在");

        GroupMember operator = getGroupMember(groupId, operatorId);
        if (operator == null) throw new RuntimeException("你不是该群成员");
        if (!"OWNER".equals(operator.getRole()) && !"ADMIN".equals(operator.getRole())) {
            throw new RuntimeException("无权限操作");
        }

        group.setGroupName(groupName);
        group.setUpdateTime(new Date());
        groupInfoMapper.updateById(group);
    }

    /**
     * 设置/取消管理员（仅 OWNER）
     */
    @Transactional
    public void setAdmin(Long groupId, Long ownerId, Long targetUserId, boolean isAdmin) {
        GroupInfo group = groupInfoMapper.selectById(groupId);
        if (group == null) throw new RuntimeException("群不存在");
        if (!ownerId.equals(group.getOwnerId())) throw new RuntimeException("只有群主可以设置管理员");

        GroupMember target = getGroupMember(groupId, targetUserId);
        if (target == null) throw new RuntimeException("目标用户不在群中");
        if ("OWNER".equals(target.getRole())) throw new RuntimeException("不能修改群主角色");

        target.setRole(isAdmin ? "ADMIN" : "MEMBER");
        groupMemberMapper.updateById(target);
        groupMemberCacheService.evict(groupId);
    }

    /**
     * 邀请成员入群（群内任何成员都可以邀请）
     */
    @Transactional
    public boolean inviteMembers(Long groupId, Long inviterId, List<Long> inviteeIds) {
        if (inviteeIds == null || inviteeIds.isEmpty()) return false;
        GroupInfo group = groupInfoMapper.selectForUpdate(groupId);
        if (group == null) throw new RuntimeException("群不存在");

        if (getGroupMember(groupId, inviterId) == null) {
            throw new RuntimeException("你不是该群成员");
        }

        int added = 0;
        for (Long inviteeId : inviteeIds.stream().filter(Objects::nonNull).distinct().toList()) {
            if (inviteeId.equals(inviterId)) continue;
            if (getGroupMember(groupId, inviteeId) != null) continue;
            if (group.getMemberCount() + added >= group.getMaxMembers()) break;

            addMemberInternal(groupId, inviteeId, "MEMBER");
            added++;
        }

        if (added > 0) {
            group.setMemberCount(group.getMemberCount() + added);
            group.setUpdateTime(new Date());
            groupInfoMapper.updateById(group);
            groupMemberCacheService.evict(groupId);
        }

        return added > 0;
    }

    /**
     * 获取群信息
     */
    public GroupInfo getGroupInfo(Long groupId) {
        return groupInfoMapper.selectById(groupId);
    }

    /**
     * 获取用户加入的所有群
     */
    public List<GroupInfo> getUserGroups(Long userId) {
        List<Long> groupIds = groupMemberMapper.selectGroupIdsByUserId(userId);
        if (groupIds == null || groupIds.isEmpty()) return Collections.emptyList();
        return groupInfoMapper.selectBatchIds(groupIds);
    }

    /**
     * 获取群成员列表（原始数据，内部使用）
     */
    public List<GroupMember> getGroupMembers(Long groupId) {
        LambdaQueryWrapper<GroupMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GroupMember::getGroupId, groupId);
        return groupMemberMapper.selectList(wrapper);
    }

    /**
     * 获取群成员详情（关联用户表，返回昵称和头像，不暴露 accountId）
     */
    public List<com.zw.sserver.model.vo.GroupMemberVO> getGroupMemberDetails(Long groupId) {
        return groupMemberMapper.selectMemberDetails(groupId);
    }

    /**
     * 查询单个群成员记录
     */
    public GroupMember getGroupMember(Long groupId, Long userId) {
        LambdaQueryWrapper<GroupMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GroupMember::getGroupId, groupId).eq(GroupMember::getUserId, userId);
        return groupMemberMapper.selectOne(wrapper);
    }

    /**
     * 群聊增量同步。新客户端使用严格单调的 seq；lastMsgId 仅用于兼容
     * 尚未保存 seq 的旧版客户端数据。
     */
    public List<GroupHistoryVO> syncGroupMessages(Long groupId, Long userId,
                                                   Long lastMsgId, Long lastSeq, int limit) {
        GroupMember member = getGroupMember(groupId, userId);
        if (member == null) return Collections.emptyList();

        LambdaQueryWrapper<GroupMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GroupMessage::getGroupId, groupId);
        if (member.getJoinTime() != null) {
            wrapper.ge(GroupMessage::getSendTime, member.getJoinTime());
        }
        boolean syncBySeq = lastSeq != null && lastSeq >= 0;
        if (syncBySeq) {
            wrapper.gt(GroupMessage::getSeq, lastSeq);
            wrapper.orderByAsc(GroupMessage::getSeq);
        } else {
            if (lastMsgId != null && lastMsgId > 0) {
                wrapper.gt(GroupMessage::getMessageId, lastMsgId);
            }
            wrapper.orderByAsc(GroupMessage::getMessageId);
        }
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        wrapper.last("LIMIT " + safeLimit);

        List<GroupMessage> messages = groupMessageMapper.selectList(wrapper);
        return messages.stream().map(msg -> {
            GroupHistoryVO vo = new GroupHistoryVO();
            BeanUtils.copyProperties(msg, vo);
            return vo;
        }).collect(Collectors.toList());
    }



    /**
     * 群聊历史消息（游标分页）
     */
    public List<GroupHistoryVO> getGroupHistory(Long groupId, Long cursor, int limit) {
        LambdaQueryWrapper<GroupMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GroupMessage::getGroupId, groupId);

        if (cursor != null) {
            wrapper.lt(GroupMessage::getMessageId, cursor);
        }

        wrapper.orderByDesc(GroupMessage::getMessageId);
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        wrapper.last("LIMIT " + safeLimit);

        List<GroupMessage> messages = groupMessageMapper.selectList(wrapper);
        Collections.reverse(messages);

        return messages.stream().map(msg -> {
            GroupHistoryVO vo = new GroupHistoryVO();
            BeanUtils.copyProperties(msg, vo);
            return vo;
        }).collect(Collectors.toList());
    }

    private void addMemberInternal(Long groupId, Long userId, String role) {
        GroupMember member = new GroupMember();
        member.setGroupId(groupId);
        member.setUserId(userId);
        member.setRole(role);
        member.setMuted(0);
        member.setLastReadSeq(0L);
        member.setJoinTime(new Date());
        groupMemberMapper.insert(member);
    }
}
