package com.zw.suser.service;

import com.zw.suser.model.entity.FriendRelation;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zw.suser.model.entity.FriendRequest;
import com.zw.suser.model.vo.FriendListVO;
import java.util.List;

/**
 * @description 针对表【tb_friend_relation(好友关系表)】的数据库操作Service
 * @createDate 2026-02-26 10:54:23
 */
public interface FriendRelationService extends IService<FriendRelation> {

    /**
     * 获取当前登录用户的好友列表
     * @return 包含好友安全信息的 VO 列表
     */
    List<FriendListVO> getMyFriendList();

    /**
     * 删除好友（双向删除关系记录）
     * @param friendId 要删除的好友用户ID
     */
    void deleteFriend(Long friendId);

}
