package com.zw.suser.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zw.scommon.exception.ApiException;
import com.zw.suser.feign.ServerFeignClient;
import com.zw.suser.model.dto.FriendApplyParam;
import com.zw.suser.model.dto.FriendHandleParam;
import com.zw.suser.model.entity.FriendRelation;
import com.zw.suser.model.entity.FriendRequest;
import com.zw.suser.model.entity.User;
import com.zw.suser.model.vo.PendingRequestVO;
import com.zw.suser.service.FriendRelationService;
import com.zw.suser.service.FriendRequestService;
import com.zw.suser.mapper.FriendRequestMapper;
import com.zw.suser.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @description 针对表【tb_friend_request(好友申请流转表)】的数据库操作Service实现
 * @createDate 2026-02-26 10:54:52
 */
@Slf4j
@Service
public class FriendRequestServiceImpl extends ServiceImpl<FriendRequestMapper, FriendRequest>
        implements FriendRequestService{

    @Autowired
    private UserService userService;

    @Autowired
    private FriendRelationService friendRelationService;

    @Autowired
    private ServerFeignClient serverFeignClient;

    // ==========================================
    // 1. 发起好友申请
    // ==========================================
    @Override
    public void apply(FriendApplyParam param) {
        long currentUserId = StpUtil.getLoginIdAsLong();

        // 获取目标用户校验
        LambdaQueryWrapper<User> userWrapper = new LambdaQueryWrapper<>();
        userWrapper.eq(User::getAccountId, param.getTargetAccountId());
        User targetUser = userService.getOne(userWrapper);
        if (targetUser == null) {
            throw new ApiException("目标用户不存在");
        }
        if (targetUser.getId() == currentUserId) {
            throw new ApiException("不能添加自己为好友");
        }

        // 检查是否已经是好友（双向检查）
        long smallId = Math.min(currentUserId, targetUser.getId());
        long bigId = Math.max(currentUserId, targetUser.getId());
        LambdaQueryWrapper<FriendRelation> relationWrapper = new LambdaQueryWrapper<>();
        relationWrapper.and(w -> w
                .eq(FriendRelation::getUserId1, smallId).eq(FriendRelation::getUserId2, bigId)
                .or()
                .eq(FriendRelation::getUserId1, bigId).eq(FriendRelation::getUserId2, smallId)
        );
        if (friendRelationService.count(relationWrapper) > 0) {
            throw new ApiException("你们已经是好友了");
        }

        // 检查是否已发送过申请且对方未处理
        LambdaQueryWrapper<FriendRequest> reqWrapper = new LambdaQueryWrapper<>();
        reqWrapper.eq(FriendRequest::getSenderId, currentUserId)
                .eq(FriendRequest::getReceiverId, targetUser.getId())
                .eq(FriendRequest::getStatus, 0);
        if (this.count(reqWrapper) > 0) {
            throw new ApiException("已发送过申请，请等待对方同意");
        }

        // 保存申请记录入库
        FriendRequest request = new FriendRequest();
        request.setSenderId(currentUserId);
        request.setReceiverId(targetUser.getId());
        request.setStatus(0);
        this.save(request);

        // 推送实时通知（新前端格式）
        try {
            JSONObject envelope = new JSONObject();
            envelope.put("type", "NEW_FRIEND_REQUEST");
            JSONObject data = new JSONObject();
            data.put("requestId", request.getId());
            data.put("fromUserId", String.valueOf(currentUserId));
            envelope.put("data", data);

            serverFeignClient.sendMessage(targetUser.getId(), envelope.toString());
            log.info("已通过 Feign 成功向 s-server 发送实时好友申请通知，目标用户: {}", targetUser.getId());

        } catch (Exception e) {
            log.error("跨微服务推送好友申请实时通知失败，目标用户: {}", targetUser.getId(), e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handle(FriendHandleParam param) {
        long currentUserId = StpUtil.getLoginIdAsLong();

        FriendRequest request = this.getById(param.getApplyId());
        if (request == null || !request.getReceiverId().equals(currentUserId)) {
            throw new ApiException("非法的申请记录");
        }
        if (request.getStatus() != 0) {
            throw new ApiException("该申请已被处理过，请勿重复操作");
        }

        boolean updateSuccess = this.update(new LambdaUpdateWrapper<FriendRequest>()
                .eq(FriendRequest::getId, param.getApplyId())
                .eq(FriendRequest::getStatus, 0)
                .set(FriendRequest::getStatus, param.getStatus())
        );

        if (!updateSuccess) {
            throw new ApiException("手速太快啦！该申请已被处理过，请勿重复操作");
        }

        if (param.getStatus() == 1) {
            long smallId = Math.min(request.getSenderId(), request.getReceiverId());
            long bigId = Math.max(request.getSenderId(), request.getReceiverId());

            FriendRelation relation1 = new FriendRelation();
            relation1.setUserId1(smallId);
            relation1.setUserId2(bigId);

            FriendRelation relation2 = new FriendRelation();
            relation2.setUserId1(bigId);
            relation2.setUserId2(smallId);

            friendRelationService.save(relation1);
            friendRelationService.save(relation2);

            // 通知申请发起方：好友申请已被同意（新前端格式，携带昵称和头像）
            try {
                User currentUser = userService.getById(currentUserId);
                JSONObject envelope = new JSONObject();
                envelope.put("type", "FRIEND_ACCEPTED");
                JSONObject data = new JSONObject();
                data.put("userId", String.valueOf(currentUserId));
                data.put("nickname", currentUser != null ? currentUser.getNickname() : "");
                data.put("avatar", currentUser != null ? currentUser.getAvatar() : "");
                envelope.put("data", data);

                serverFeignClient.sendMessage(request.getSenderId(), envelope.toString());
                log.info("已通知申请发起方好友申请被同意，目标用户: {}", request.getSenderId());
            } catch (Exception e) {
                log.error("推送好友同意通知失败，目标用户: {}", request.getSenderId(), e);
            }
        }
    }

    // ==========================================
    // 3. 获取待处理列表
    // ==========================================
    @Override
    public List<PendingRequestVO> getPendingRequests() {
        long currentUserId = StpUtil.getLoginIdAsLong();

        LambdaQueryWrapper<FriendRequest> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FriendRequest::getReceiverId, currentUserId)
                .eq(FriendRequest::getStatus, 0)
                .orderByDesc(FriendRequest::getId);
        List<FriendRequest> requests = this.list(wrapper);

        if (requests.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> senderIds = requests.stream()
                .map(FriendRequest::getSenderId)
                .collect(Collectors.toList());

        List<User> senders = userService.listByIds(senderIds);

        Map<Long, User> userMap = senders.stream()
                .collect(Collectors.toMap(User::getId, user -> user));

        return requests.stream().map(req -> {
            PendingRequestVO vo = new PendingRequestVO();
            vo.setApplyId(req.getId());
            vo.setSenderId(req.getSenderId());
            vo.setCreateTime(req.getCreateTime());

            User sender = userMap.get(req.getSenderId());
            if (sender != null) {
                vo.setApplicantAccountId(sender.getAccountId());
                vo.setApplicantNickname(sender.getNickname());
                vo.setApplicantAvatar(sender.getAvatar());
            }
            return vo;
        }).collect(Collectors.toList());
    }
}