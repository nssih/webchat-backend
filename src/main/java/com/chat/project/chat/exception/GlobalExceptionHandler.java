package com.chat.project.chat.exception;

import com.chat.project.chat.dto.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 400 - 业务参数错误（如用户名已存在、两次密码不一致等） */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleBusiness(BusinessException e) {
        return ApiResponse.fail(e.getMessage());
    }

    /** 401 - 认证失败（密码错误、token 失效等） */
    @ExceptionHandler(AuthException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<?> handleAuth(AuthException e) {
        return ApiResponse.fail(e.getMessage());
    }

    /** 403 - 权限不足（非群主邀请、越权操作等） */
    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<?> handleForbidden(ForbiddenException e) {
        return ApiResponse.fail(e.getMessage());
    }

    /** 404 - 资源不存在（用户、群组等） */
    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<?> handleNotFound(NotFoundException e) {
        return ApiResponse.fail(e.getMessage());
    }

    /** 400 - Bean Validation 校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ApiResponse.fail(msg);
    }

    /** 400 - 缺少必填 QueryParam（如 /api/users/search 不传 keyword） */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleMissingParam(MissingServletRequestParameterException e) {
        return ApiResponse.fail("缺少必填参数：" + e.getParameterName());
    }

    /** 400 - 数据库唯一约束冲突（并发注册同名用户等竞态场景） */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleDataIntegrity(DataIntegrityViolationException e) {
        String msg = e.getMessage() != null && e.getMessage().contains("username")
                ? "用户名已被使用"
                : "数据冲突，请重试";
        return ApiResponse.fail(msg);
    }

    /** 500 - 未预料到的异常，记录日志 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<?> handleGeneral(Exception e) {
        log.error("未处理异常", e);
        return ApiResponse.fail("服务器内部错误");
    }
}
