package com.zw.scommon.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.zw.scommon.api.CommonResult;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器：统一拦截异常，防止堆栈信息泄露给前端
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Sa-Token 登录态异常：返回真实 401，便于前端统一清理登录态。
     */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<CommonResult<String>> handleNotLoginException(NotLoginException e) {
        log.warn("认证失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(CommonResult.unauthorized(e.getMessage()));
    }

    /**
     * Sa-Token 权限异常。
     */
    @ExceptionHandler({NotPermissionException.class, NotRoleException.class})
    public ResponseEntity<CommonResult<String>> handleForbiddenException(RuntimeException e) {
        log.warn("权限不足: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(CommonResult.forbidden(e.getMessage()));
    }

    /**
     * 业务异常
     */
    @ExceptionHandler(ApiException.class)
    public CommonResult<String> handleApiException(ApiException e) {
        log.warn("业务异常: {}", e.getMessage());
        return CommonResult.failed(e.getMessage());
    }

    /**
     * Bean Validation 校验失败（@RequestBody + @Valid）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public CommonResult<String> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", message);
        return CommonResult.failed(message);
    }

    /**
     * 单参数校验失败（@RequestParam + @NotBlank 等）
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public CommonResult<String> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数约束违反: {}", message);
        return CommonResult.failed(message);
    }

    /**
     * 兜底：未知异常
     */
    @ExceptionHandler(Exception.class)
    public CommonResult<String> handleException(Exception e) {
        log.error("系统未知异常:", e);
        return CommonResult.failed("系统繁忙，请稍后再试");
    }
}
