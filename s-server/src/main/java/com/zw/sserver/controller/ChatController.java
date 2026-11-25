package com.zw.sserver.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.zw.scommon.api.CommonResult;
import com.zw.sserver.application.message.ChatHttpApplicationService;
import com.zw.sserver.model.vo.SessionVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 聊天 HTTP 接口，只负责鉴权上下文与请求参数适配。
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatHttpApplicationService chatHttpApplicationService;

    public ChatController(ChatHttpApplicationService chatHttpApplicationService) {
        this.chatHttpApplicationService = chatHttpApplicationService;
    }

    @GetMapping("/sessions")
    public CommonResult<List<SessionVO>> getSessions() {
        return chatHttpApplicationService.getSessions(StpUtil.getLoginIdAsLong());
    }

    @GetMapping("/{sessionId}/history")
    public CommonResult<Map<String, Object>> getHistoryBySession(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "size", defaultValue = "20") Integer size) {
        return chatHttpApplicationService.getHistory(StpUtil.getLoginIdAsLong(), sessionId, cursor, size);
    }

    @PostMapping("/{sessionId}/read")
    public CommonResult<String> markAsReadBySession(
            @PathVariable("sessionId") Long sessionId,
            @RequestBody(required = false) Map<String, Object> body) {
        return chatHttpApplicationService.markPrivateRead(StpUtil.getLoginIdAsLong(), sessionId, body);
    }

    @PostMapping("/{sessionId}/messages")
    public CommonResult<Map<String, Object>> sendMessageHttp(
            @PathVariable("sessionId") String sessionId,
            @RequestBody Map<String, Object> body) {
        return chatHttpApplicationService.sendMessage(StpUtil.getLoginIdAsLong(), sessionId, body);
    }

    @PostMapping("/{sessionId}/messages/{messageId}/recall")
    public CommonResult<String> recallMessage(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("messageId") Long messageId) {
        return chatHttpApplicationService.recallMessage(StpUtil.getLoginIdAsLong(), sessionId, messageId);
    }

    @GetMapping("/{sessionId}/sync")
    public CommonResult<Map<String, Object>> syncBySession(
            @PathVariable("sessionId") Long sessionId,
            @RequestParam(value = "lastMsgId", defaultValue = "0") Long lastMsgId,
            @RequestParam(value = "lastSeq", required = false) Long lastSeq,
            @RequestParam(value = "limit", defaultValue = "50") Integer limit) {
        return chatHttpApplicationService.syncPrivateMessages(
                StpUtil.getLoginIdAsLong(), sessionId, lastMsgId, lastSeq, limit);
    }

    @GetMapping("/search")
    public CommonResult<List<Map<String, Object>>> searchMessages(
            @RequestParam("keyword") String keyword,
            @RequestParam(value = "limit", defaultValue = "50") Integer limit) {
        return chatHttpApplicationService.searchMessages(StpUtil.getLoginIdAsLong(), keyword, limit);
    }
}
