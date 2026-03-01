package com.smartcampus.exception;

import com.smartcampus.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常 - ✅ 修改为动态HTTP状态码
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());

        // 根据业务异常中的 code 返回对应的 HTTP 状态码
        HttpStatus status = HttpStatus.BAD_REQUEST; // 默认 400

        if (e.getCode() == 401) {
            status = HttpStatus.UNAUTHORIZED;
        } else if (e.getCode() == 403) {
            status = HttpStatus.FORBIDDEN;
        } else if (e.getCode() == 404) {
            status = HttpStatus.NOT_FOUND;
        } else if (e.getCode() >= 500) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        return new ResponseEntity<>(
                ApiResponse.error(e.getCode(), e.getMessage()),
                status
        );
    }

    /**
     * 处理参数校验异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("参数校验失败: {}", message);
        return ApiResponse.error(400, message);
    }

    /**
     * 处理 IllegalArgumentException（用于Token缺失/格式错误）
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("认证失败: {}", e.getMessage());
        return ApiResponse.error(401, e.getMessage());
    }

    /**
     * 处理 RuntimeException（用于Token无效/解析失败）
     */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleRuntimeException(RuntimeException e) {
        if (e.getMessage() != null &&
                (e.getMessage().contains("Token") ||
                        e.getMessage().contains("token") ||
                        e.getMessage().contains("JWT"))) {
            log.warn("Token认证失败: {}", e.getMessage());
            return ApiResponse.error(401, e.getMessage());
        }
        log.error("系统异常: ", e);
        return ApiResponse.error(500, "服务器内部错误");
    }

    /**
     * 处理所有其他异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("系统异常: ", e);
        return ApiResponse.error(500, "服务器内部错误");
    }
}