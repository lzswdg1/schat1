package com.zw.sserver.controller;

import com.zw.scommon.api.CommonResult;
import com.zw.sserver.config.DeliveryOpsAccessInterceptor;
import com.zw.sserver.service.DeliveryOperationsService;
import com.zw.sserver.service.DeliveryOperationsService.RetryItem;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@RestController
@RequestMapping("/ops/delivery")
@ConditionalOnProperty(prefix = "schat.delivery-ops", name = "enabled", havingValue = "true")
public class DeliveryOperationsController {

    private final DeliveryOperationsService service;

    public DeliveryOperationsController(DeliveryOperationsService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public CommonResult<?> summary() {
        return CommonResult.success(service.summary());
    }

    @GetMapping("/failed")
    public CommonResult<?> failed(@RequestParam(defaultValue = "0") long afterId,
                                   @RequestParam(defaultValue = "50") int limit) {
        return CommonResult.success(service.failed(afterId, limit));
    }

    @GetMapping("/audit")
    public CommonResult<?> audit(@RequestParam(defaultValue = "0") long afterId,
                                  @RequestParam(defaultValue = "50") int limit) {
        return CommonResult.success(service.audit(afterId, limit));
    }

    @PostMapping("/retry")
    public CommonResult<?> retry(@RequestBody RetryRequest body,
                                 @RequestAttribute(DeliveryOpsAccessInterceptor.ACTOR) String actor,
                                 @RequestAttribute(DeliveryOpsAccessInterceptor.REQUEST_ID) String requestId) {
        return CommonResult.success(service.retry(body.items(), body.reason(), actor, requestId));
    }

    @GetMapping("/cleanup")
    public CommonResult<?> cleanupPreview(@RequestParam(defaultValue = "30") int olderThanDays) {
        return CommonResult.success(service.cleanupPreview(olderThanDays));
    }

    @PostMapping("/cleanup")
    public CommonResult<?> cleanup(@RequestBody CleanupRequest body,
                                   @RequestAttribute(DeliveryOpsAccessInterceptor.ACTOR) String actor,
                                   @RequestAttribute(DeliveryOpsAccessInterceptor.REQUEST_ID) String requestId) {
        if (body.olderThanDays() == null || body.limit() == null) {
            throw new IllegalArgumentException("必须指定保留天数和批次大小");
        }
        return CommonResult.success(service.cleanup(body.olderThanDays(), body.limit(), body.reason(), actor, requestId));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<CommonResult<?>> badRequest(Exception exception) {
        return error(400, "运维请求参数无效");
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<CommonResult<?>> unavailable(RuntimeException exception) {
        return error(503, "投递运维数据库暂不可用");
    }

    private static ResponseEntity<CommonResult<?>> error(int status, String message) {
        var result = CommonResult.failed(message);
        result.setCode(status);
        return ResponseEntity.status(status).body(result);
    }

    public record RetryRequest(List<RetryItem> items, String reason) { }
    public record CleanupRequest(Integer olderThanDays, Integer limit, String reason) { }
}
