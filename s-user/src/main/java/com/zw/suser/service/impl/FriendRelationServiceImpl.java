package com.zw.suser.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zw.scommon.exception.ApiException;
import com.zw.suser.feign.ServerFeignClient;
import com.zw.suser.mapper.UserMapper;
import com.zw.suser.model.entity.FriendRelation;
import com.zw.suser.model.entity.User;
import com.zw.suser.model.vo.FriendListVO;
import com.zw.suser.service.FriendRelationService;
import com.zw.suser.mapper.FriendRelationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @description 针对表【tb_friend_relation(好友关系表)】的数据库操作Service实现
 * @createDate 2026-02-26 10:54:23
 */
@Slf4j
@Service
public class FriendRelationServiceImpl extends ServiceImpl<FriendRelationMapper, FriendRelation>
        implements FriendRelationService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ServerFeignClient serverFeignClient;

    @Override
    public List<FriendListVO> getMyFriendList() {
        // 1. 获取当前登录用户的 ID
        Long myId = StpUtil.getLoginIdAsLong();

        // 2. 去关系表里查出所有包含我的记录
        // 因为继承了 ServiceImpl，直接用 this.list() 即可
        QueryWrapper<FriendRelation> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id_1", myId).or().eq("user_id_2", myId);
        List<FriendRelation> relations = this.list(wrapper);

        // 3. 提取出朋友们的 ID
        List<Long> friendIds = relations.stream().map(relation -> {
            // 如果我是 user1，那朋友就是 user2；反之亦然
            return relation.getUserId1().equals(myId) ? relation.getUserId2() : relation.getUserId1();
        }).collect(Collectors.toList());

        // 如果没朋友，直接返回空集合，防止下面查 user 表时 IN() 里面为空报错
        if (friendIds.isEmpty()) {
            return new ArrayList<>();
        }

        // 4. 去 User 表查出朋友们的具体信息
        List<User> friendUsers = userMapper.selectBatchIds(friendIds);

        // 5. 将大而全的 User 实体转换为精简安全的 VO (脱敏操作，绝不能把密码传给前端)
        return friendUsers.stream().map(user -> {
            FriendListVO vo = new FriendListVO();
            BeanUtils.copyProperties(user, vo);
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteFriend(Long friendId) {
        Long myId = StpUtil.getLoginIdAsLong();

        if (myId.equals(friendId)) {
            throw new ApiException("不能删除自己");
        }

        // 删除双向好友关系
        QueryWrapper<FriendRelation> wrapper = new QueryWrapper<>();
        wrapper.and(w -> w.eq("user_id_1", myId).eq("user_id_2", friendId))
               .or(w -> w.eq("user_id_1", friendId).eq("user_id_2", myId));

        int deleted = this.baseMapper.delete(wrapper);
        if (deleted == 0) {
            throw new ApiException("该用户不是你的好友");
        }

        // 删除双方的聊天记录
        try {
            serverFeignClient.deleteMessages(myId, friendId);
            log.info("已删除用户 {} 和 {} 之间的聊天记录", myId, friendId);
        } catch (Exception e) {
            log.error("删除聊天记录失败，用户: {} 和 {}", myId, friendId, e);
        }

        // 通知对方刷新好友列表（统一使用新前端信封格式）
        try {
            JSONObject envelope = new JSONObject();
            envelope.put("type", "FRIEND_DELETED");
            JSONObject data = new JSONObject();
            data.put("userId", String.valueOf(myId));
            envelope.put("data", data);

            serverFeignClient.sendMessage(friendId, envelope.toString());
            log.info("已通知用户 {} 好友关系已被删除", friendId);
        } catch (Exception e) {
            log.error("推送好友删除通知失败，目标用户: {}", friendId, e);
        }
    }

}




