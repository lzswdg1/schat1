package com.zw.suser.controller;

import com.zw.scommon.api.CommonResult;
import com.zw.suser.model.dto.FriendApplyParam;
import com.zw.suser.model.dto.FriendHandleParam;
import com.zw.suser.model.vo.FriendListVO;
import com.zw.suser.model.vo.PendingRequestVO;
import com.zw.suser.service.FriendRelationService;
import com.zw.suser.service.FriendRequestService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/friend")
public class FriendController {

    private final FriendRequestService friendRequestService;
    private final FriendRelationService friendRelationService;

    public FriendController(FriendRequestService friendRequestService,
                            FriendRelationService friendRelationService) {
        this.friendRequestService = friendRequestService;
        this.friendRelationService = friendRelationService;
    }

    @PostMapping("/apply")
    public CommonResult<String> apply(@RequestBody @Valid FriendApplyParam param) {
        friendRequestService.apply(param);
        return CommonResult.success("申请已发送，请等待对方通过");
    }

    @PostMapping("/handle")
    public CommonResult<String> handle(@RequestBody @Valid FriendHandleParam param) {
        friendRequestService.handle(param);
        return CommonResult.success("处理成功");
    }

    @GetMapping("/list")
    public CommonResult<List<FriendListVO>> list() {
        List<FriendListVO> friendList = friendRelationService.getMyFriendList();
        return CommonResult.success(friendList);
    }

    /**
     * 📬 拉取发给我的“新朋友”申请列表
     * GET /api/friend/my-pending
     */
    @GetMapping("/my-pending")
    public CommonResult<List<PendingRequestVO>> getMyPendingRequests() {
        List<PendingRequestVO> list = friendRequestService.getPendingRequests();
        return CommonResult.success(list);
    }

    @DeleteMapping("/delete")
    public CommonResult<String> deleteFriend(@RequestParam("friendId") Long friendId) {
        friendRelationService.deleteFriend(friendId);
        return CommonResult.success("好友已删除");
    }
}
