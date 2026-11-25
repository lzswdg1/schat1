package com.zw.suser.service;

import com.zw.suser.model.dto.FriendApplyParam;
import com.zw.suser.model.dto.FriendHandleParam;
import com.zw.suser.model.entity.FriendRequest;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zw.suser.model.vo.PendingRequestVO;

import java.util.List;

/**
* @description 针对表【tb_friend_request(好友申请流转表)】的数据库操作Service
* @createDate 2026-02-26 10:54:52
*/
public interface FriendRequestService extends IService<FriendRequest> {
    /**
     * 发起好友申请
     * @param param 包含目标账号ID和申请理由
     */
    void apply(FriendApplyParam param);

    /**
     * 处理好友申请（同意或拒绝）
     * @param param 包含申请记录的主键ID和处理结果(1-同意, 2-拒绝)
     */
    void handle(FriendHandleParam param);
    List<PendingRequestVO> getPendingRequests();
}
