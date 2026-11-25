package com.zw.sserver.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.zw.scommon.api.CommonResult;
import com.zw.sserver.application.session.SessionCacheInvalidationService;
import com.zw.sserver.model.dto.CreateGroupDTO;
import com.zw.sserver.model.entity.GroupInfo;
import com.zw.sserver.model.entity.GroupMember;
import com.zw.sserver.model.dto.RouteMessage;
import com.zw.sserver.registry.InstanceRegistry;
import com.zw.sserver.router.CrossNodeRouter;
import com.zw.sserver.service.GroupMemberCacheService;
import com.zw.sserver.service.GroupReadCursorService;
import com.zw.sserver.service.GroupService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;
import com.zw.sserver.model.vo.GroupHistoryVO;

/**
 * 当前前端使用的群聊 HTTP 接口。
 */
@RestController
public class GroupController {

    private static final Logger log = LoggerFactory.getLogger(GroupController.class);

    private final GroupService groupService;
    private final GroupMemberCacheService groupMemberCacheService;
    private final GroupReadCursorService groupReadCursorService;
    private final CrossNodeRouter crossNodeRouter;
    private final InstanceRegistry instanceRegistry;
    private final ObjectMapper objectMapper;
    private final SessionCacheInvalidationService sessionCacheInvalidationService;

    public GroupController(GroupService groupService,
                           GroupMemberCacheService groupMemberCacheService,
                           GroupReadCursorService groupReadCursorService,
                           CrossNodeRouter crossNodeRouter,
                           InstanceRegistry instanceRegistry,
                           ObjectMapper objectMapper,
                           SessionCacheInvalidationService sessionCacheInvalidationService) {
        this.groupService = groupService;
        this.groupMemberCacheService = groupMemberCacheService;
        this.groupReadCursorService = groupReadCursorService;
        this.crossNodeRouter = crossNodeRouter;
        this.instanceRegistry = instanceRegistry;
        this.objectMapper = objectMapper;
        this.sessionCacheInvalidationService = sessionCacheInvalidationService;
    }

    // ==================== 当前前端使用的群组接口 ====================

    /**
     * 踢出群成员
     * POST /api/group/kick
     * Body: { "groupId": 100, "userId": 2 }
     */
    @PostMapping("/group/kick")
    public CommonResult<String> kickMember(@RequestBody Map<String, Long> body) {
        Long myId = StpUtil.getLoginIdAsLong();
        Long groupId = body.get("groupId");
        Long userId = body.get("userId");
        if (groupId == null || userId == null) return CommonResult.failed("参数缺失");
        try {
            groupService.kickMember(groupId, myId, userId);
            sessionCacheInvalidationService.evictUser(userId);
            // 通知被踢的人
            notifyGroupEvent("GROUP_KICKED", groupId, myId, List.of(userId));
            return CommonResult.success("踢出成功");
        } catch (RuntimeException e) {
            return CommonResult.failed(e.getMessage());
        }
    }

    /**
     * 转让群主
     * POST /api/group/transferOwner
     * Body: { "groupId": 100, "newOwnerId": 3 }
     */
    @PostMapping("/group/transferOwner")
    public CommonResult<String> transferOwner(@RequestBody Map<String, Long> body) {
        Long myId = StpUtil.getLoginIdAsLong();
        Long groupId = body.get("groupId");
        Long newOwnerId = body.get("newOwnerId");
        if (groupId == null || newOwnerId == null) return CommonResult.failed("参数缺失");
        try {
            groupService.transferOwner(groupId, myId, newOwnerId);
            return CommonResult.success("转让成功");
        } catch (RuntimeException e) {
            return CommonResult.failed(e.getMessage());
        }
    }

    /**
     * 退出群聊（RESTful body 风格）
     * POST /api/group/leave
     * Body: { "groupId": 100 }
     */
    @PostMapping("/group/leave")
    public CommonResult<String> leaveGroupRest(@RequestBody Map<String, Long> body) {
        Long myId = StpUtil.getLoginIdAsLong();
        Long groupId = body.get("groupId");
        if (groupId == null) return CommonResult.failed("参数缺失");
        try {
            boolean ok = groupService.leaveGroup(groupId, myId);
            if (ok) {
                sessionCacheInvalidationService.evictUser(myId);
                // 通知群内其他成员
                List<Long> remainingMembers = groupService.getGroupMembers(groupId).stream()
                        .map(GroupMember::getUserId).collect(Collectors.toList());
                notifyGroupEvent("GROUP_MEMBER_LEFT", groupId, myId, remainingMembers);
            }
            return ok ? CommonResult.success("退出成功") : CommonResult.failed("退出失败");
        } catch (RuntimeException e) {
            return CommonResult.failed(e.getMessage());
        }
    }

    /**
     * 群聊增量同步。
     * 兼容当前前端传入的纯数字 groupId 或 group_ 前缀会话 ID。
     */
    @GetMapping("/group/{groupId}/sync")
    public CommonResult<Map<String, Object>> syncGroupMessages(
            @PathVariable("groupId") String groupId,
            @RequestParam(value = "lastMsgId", defaultValue = "0") Long lastMsgId,
            @RequestParam(value = "lastSeq", required = false) Long lastSeq,
            @RequestParam(value = "limit", defaultValue = "50") Integer limit) {
        Long realGroupId = parseGroupId(groupId);
        if (realGroupId == null) {
            return CommonResult.failed("无效的 groupId");
        }
        Long myId = StpUtil.getLoginIdAsLong();
        if (!groupMemberCacheService.isMember(realGroupId, myId)) {
            return CommonResult.failed("你不是该群成员");
        }
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        var messages = groupService.syncGroupMessages(realGroupId, myId, lastMsgId, lastSeq, safeLimit);
        List<Map<String, Object>> frontendMessages = messages.stream()
                .map(this::toFrontendGroupMessage)
                .collect(Collectors.toList());
        Map<String, Object> result = new HashMap<>();
        result.put("messages", frontendMessages);
        result.put("hasMore", messages.size() >= safeLimit);
        result.put("nextSeq", messages.isEmpty() ? lastSeq : messages.get(messages.size() - 1).getSeq());
        result.put("nextMsgId", messages.isEmpty() ? lastMsgId : messages.get(messages.size() - 1).getMessageId());
        return CommonResult.success(result);
    }

    /** POST /api/group/create */
    @PostMapping("/group/create")
    public CommonResult<GroupInfo> createGroup(@Valid @RequestBody CreateGroupDTO dto) {
        Long myId = StpUtil.getLoginIdAsLong();
        List<Long> memberIds = dto.getMemberIds() != null ? dto.getMemberIds() : Collections.emptyList();
        GroupInfo group = groupService.createGroup(myId, dto.getGroupName().trim(), memberIds);
        sessionCacheInvalidationService.evictGroupMembers(withOperator(myId, memberIds));
        notifyGroupCreated(group, myId, memberIds);
        return CommonResult.success(group);
    }

    /** POST /api/group/dismiss?groupId=100 */
    @PostMapping("/group/dismiss")
    public CommonResult<String> dismissGroup(@RequestParam("groupId") Long groupId) {
        Long myId = StpUtil.getLoginIdAsLong();
        List<Long> memberIds = groupService.dismissGroup(groupId, myId);
        if (memberIds == null) return CommonResult.failed("无权限或群不存在");
        sessionCacheInvalidationService.evictGroupMembers(memberIds);
        // 通知所有成员群已解散
        notifyGroupEvent("GROUP_DISMISSED", groupId, myId, memberIds);
        return CommonResult.success("解散成功");
    }

    /** GET /api/group/info?groupId=100 */
    @GetMapping("/group/info")
    public CommonResult<GroupInfo> getGroupInfo(@RequestParam("groupId") Long groupId) {
        Long myId = StpUtil.getLoginIdAsLong();
        if (!groupMemberCacheService.isMember(groupId, myId)) return CommonResult.failed("你不是该群成员");
        GroupInfo info = groupService.getGroupInfo(groupId);
        return info != null ? CommonResult.success(info) : CommonResult.failed("群不存在");
    }

    /** GET /api/group/members?groupId=100 */
    @GetMapping("/group/members")
    public CommonResult<List<com.zw.sserver.model.vo.GroupMemberVO>> getGroupMembers(@RequestParam("groupId") Long groupId) {
        Long myId = StpUtil.getLoginIdAsLong();
        if (!groupMemberCacheService.isMember(groupId, myId)) return CommonResult.failed("你不是该群成员");
        return CommonResult.success(groupService.getGroupMemberDetails(groupId));
    }

    /** GET /api/group/myGroups */
    @GetMapping("/group/myGroups")
    public CommonResult<List<GroupInfo>> getMyGroups() {
        Long myId = StpUtil.getLoginIdAsLong();
        return CommonResult.success(groupService.getUserGroups(myId));
    }

    /** POST /api/group/read?groupId=100&messageId=xxx */
    @PostMapping("/group/read")
    public CommonResult<String> markGroupRead(
            @RequestParam("groupId") String groupId,
            @RequestParam(value = "messageId", required = false) Long messageId) {
        Long realGroupId = parseGroupId(groupId);
        if (realGroupId == null) {
            return CommonResult.failed("无效的 groupId");
        }
        Long myId = StpUtil.getLoginIdAsLong();
        if (!groupMemberCacheService.isMember(realGroupId, myId)) return CommonResult.failed("你不是该群成员");
        if (messageId == null || messageId <= 0) {
            return CommonResult.success("无需操作");
        }
        groupReadCursorService.updateCursor(myId, realGroupId, messageId);
        sessionCacheInvalidationService.evictUser(myId);
        return CommonResult.success("已读");
    }

    /**
     * 修改群头像
     * POST /api/group/avatar
     * Body: { "groupId": 100, "avatar": "/file/xxx.jpg" }
     */
    @PostMapping("/group/avatar")
    public CommonResult<String> updateAvatar(@RequestBody Map<String, Object> body) {
        Long myId = StpUtil.getLoginIdAsLong();
        Long groupId = Long.valueOf(body.get("groupId").toString());
        String avatar = (String) body.getOrDefault("avatar", "");
        if (avatar.isEmpty()) return CommonResult.failed("头像地址不能为空");
        try {
            groupService.updateAvatar(groupId, myId, avatar);
            sessionCacheInvalidationService.evictGroupMembers(groupMemberCacheService.getMemberIds(groupId));
            return CommonResult.success("群头像已更新");
        } catch (RuntimeException e) {
            return CommonResult.failed(e.getMessage());
        }
    }

    /**
     * 修改群公告
     * POST /api/group/notice
     * Body: { "groupId": 100, "notice": "新公告内容" }
     */
    @PostMapping("/group/notice")
    public CommonResult<String> updateNotice(@RequestBody Map<String, Object> body) {
        Long myId = StpUtil.getLoginIdAsLong();
        Long groupId = Long.valueOf(body.get("groupId").toString());
        String notice = (String) body.getOrDefault("notice", "");
        try {
            groupService.updateNotice(groupId, myId, notice);
            return CommonResult.success("公告已更新");
        } catch (RuntimeException e) {
            return CommonResult.failed(e.getMessage());
        }
    }

    /**
     * 修改群名称
     * POST /api/group/rename
     * Body: { "groupId": 100, "groupName": "新群名" }
     */
    @PostMapping("/group/rename")
    public CommonResult<String> renameGroup(@RequestBody Map<String, Object> body) {
        Long myId = StpUtil.getLoginIdAsLong();
        Long groupId = Long.valueOf(body.get("groupId").toString());
        String groupName = (String) body.get("groupName");
        if (groupName == null || groupName.trim().isEmpty()) return CommonResult.failed("群名称不能为空");
        try {
            groupService.updateGroupName(groupId, myId, groupName.trim());
            sessionCacheInvalidationService.evictGroupMembers(groupMemberCacheService.getMemberIds(groupId));
            return CommonResult.success("群名称已更新");
        } catch (RuntimeException e) {
            return CommonResult.failed(e.getMessage());
        }
    }

    /**
     * 设置/取消管理员
     * POST /api/group/setAdmin
     * Body: { "groupId": 100, "userId": 2, "isAdmin": true }
     */
    @PostMapping("/group/setAdmin")
    public CommonResult<String> setAdmin(@RequestBody Map<String, Object> body) {
        Long myId = StpUtil.getLoginIdAsLong();
        Long groupId = Long.valueOf(body.get("groupId").toString());
        Long userId = Long.valueOf(body.get("userId").toString());
        Boolean isAdmin = (Boolean) body.getOrDefault("isAdmin", true);
        try {
            groupService.setAdmin(groupId, myId, userId, isAdmin);
            return CommonResult.success(isAdmin ? "已设为管理员" : "已取消管理员");
        } catch (RuntimeException e) {
            return CommonResult.failed(e.getMessage());
        }
    }

    /**
     * 邀请成员入群
     * POST /api/group/invite
     * Body: { "groupId": 100, "memberIds": [2, 3, 4] }
     */
    @PostMapping("/group/invite")
    public CommonResult<String> inviteMembers(@RequestBody Map<String, Object> body) {
        Long myId = StpUtil.getLoginIdAsLong();
        Long groupId = Long.valueOf(body.get("groupId").toString());
        @SuppressWarnings("unchecked")
        List<Number> memberIdNums = (List<Number>) body.getOrDefault("memberIds", List.of());
        List<Long> memberIds = memberIdNums.stream().map(Number::longValue).collect(Collectors.toList());
        try {
            boolean ok = groupService.inviteMembers(groupId, myId, memberIds);
            if (ok) {
                sessionCacheInvalidationService.evictGroupMembers(memberIds);
                // 通知被邀请的成员
                GroupInfo group = groupService.getGroupInfo(groupId);
                if (group != null) {
                    notifyGroupCreated(group, myId, memberIds);
                }
            }
            return ok ? CommonResult.success("邀请成功") : CommonResult.failed("邀请失败");
        } catch (RuntimeException e) {
            return CommonResult.failed(e.getMessage());
        }
    }

    // ==================== Private Helpers ====================

    /**
     * 通知被拉入群的成员刷新群列表
     * 推送 GROUP_CREATED 事件，前端收到后自动刷新群组列表
     */
    private void notifyGroupCreated(GroupInfo group, Long creatorId, List<Long> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) return;
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "type", "GROUP_CREATED",
                    "data", Map.of(
                            "groupId", group.getGroupId(),
                            "groupName", group.getGroupName(),
                            "creatorId", String.valueOf(creatorId)
                    )
            ));
            for (Long memberId : memberIds) {
                if (memberId.equals(creatorId)) continue;
                RouteMessage routeMsg = new RouteMessage();
                routeMsg.setType("NOTICE");
                routeMsg.setTargetUserId(memberId);
                routeMsg.setPayload(payload);
                routeMsg.setSourceInstanceId(instanceRegistry.getInstanceId());
                crossNodeRouter.route(routeMsg);
            }
            log.info("已通知 {} 个成员加入群 {}", memberIds.size() - 1, group.getGroupId());
        } catch (Exception e) {
            log.error("群创建通知推送失败: groupId={}", group.getGroupId(), e);
        }
    }

    private List<Long> withOperator(Long operatorId, List<Long> memberIds) {
        List<Long> all = new ArrayList<>();
        if (operatorId != null) {
            all.add(operatorId);
        }
        if (memberIds != null) {
            all.addAll(memberIds);
        }
        return all.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
    }

    Map<String, Object> toFrontendGroupMessage(GroupHistoryVO message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", message.getMessageId());
        result.put("sessionId", "group_" + message.getGroupId());
        result.put("senderId", String.valueOf(message.getSenderId()));
        result.put("timestamp", message.getSendTime() != null ? message.getSendTime().getTime() : 0L);
        result.put("seq", message.getSeq());

        String type = "text";
        String content = "";
        boolean recalled = false;
        Long quoteMsgId = message.getQuoteMsgId();
        String quoteContent = message.getQuoteContent();
        if (message.getCipherContent() != null && !message.getCipherContent().isBlank()) {
            try {
                var node = objectMapper.readTree(message.getCipherContent());
                if (node.has("msgType")) {
                    type = node.get("msgType").asText("GROUP_TEXT")
                            .replace("GROUP_", "")
                            .toLowerCase(Locale.ROOT);
                }
                if (node.has("content")) content = node.get("content").asText("");
                if (node.has("recalled")) recalled = node.get("recalled").asBoolean(false);
                if (quoteMsgId == null && node.hasNonNull("quoteMsgId")) {
                    quoteMsgId = node.get("quoteMsgId").asLong();
                }
                if (quoteContent == null && node.hasNonNull("quoteContent")) {
                    quoteContent = node.get("quoteContent").asText("");
                }
            } catch (Exception e) {
                content = message.getCipherContent();
            }
        }
        result.put("type", recalled ? "recall" : type);
        result.put("content", content);
        result.put("recalled", recalled);
        if (quoteMsgId != null) {
            result.put("quote", Map.of(
                    "messageId", quoteMsgId,
                    "content", quoteContent != null ? quoteContent : ""));
        } else {
            result.put("quote", null);
        }
        return result;
    }

    private Long parseGroupId(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return null;
        }
        String raw = groupId.startsWith("group_") ? groupId.substring("group_".length()) : groupId;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 通用群事件通知（退出、解散、踢人等）
     */
    private void notifyGroupEvent(String eventType, Long groupId, Long operatorId, List<Long> targetUserIds) {
        if (targetUserIds == null || targetUserIds.isEmpty()) return;
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "type", eventType,
                    "data", Map.of(
                            "groupId", groupId,
                            "operatorId", String.valueOf(operatorId)
                    )
            ));
            for (Long userId : targetUserIds) {
                if (userId.equals(operatorId)) continue;
                RouteMessage routeMsg = new RouteMessage();
                routeMsg.setType("NOTICE");
                routeMsg.setTargetUserId(userId);
                routeMsg.setPayload(payload);
                routeMsg.setSourceInstanceId(instanceRegistry.getInstanceId());
                crossNodeRouter.route(routeMsg);
            }
        } catch (Exception e) {
            log.error("群事件通知推送失败: type={}, groupId={}", eventType, groupId, e);
        }
    }
}
