package com.fridayclass.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理。
 *
 * <p>响应策略（与 docs/api-contract.md 的前端解包逻辑对齐）：
 * <ul>
 *   <li>业务/参数校验错误 → HTTP 200 + code 非 0，前端可读到 message 并展示；</li>
 *   <li>认证失败 → HTTP 401（前端拦截器据此清 token 并跳登录）；</li>
 *   <li>无权限 → HTTP 403；</li>
 *   <li>未预期异常 → HTTP 500，仅记录日志、不外泄堆栈细节。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusiness(BusinessException ex) {
        return ApiResponse.fail(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getDefaultMessage() == null ? e.getField() : e.getDefaultMessage())
                .collect(Collectors.joining("；"));
        return ApiResponse.fail(400, message.isBlank() ? "参数不合法" : message);
    }

    /** 请求体不是合法 JSON / 编码错误：属于客户端问题，返回 400 而非 500。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<Void> handleUnreadable(HttpMessageNotReadableException ex) {
        return ApiResponse.fail(400, "请求体格式不正确");
    }

    /**
     * 路径/查询参数类型不匹配，例如 {@code ?page=abc} 或 {@code /api/courseware/abc}。
     * 属于客户端错误，必须返回 400；否则会落到兜底分支变成 500 并打整段错误堆栈
     * （可被用来灌日志）。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ApiResponse<Void> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ApiResponse.fail(400, "参数格式不正确：" + ex.getName());
    }

    /**
     * 上传文件超限 / multipart 请求格式错误。
     *
     * <p>Tomcat 超限抛的是 {@code SizeLimitExceededException}，Spring 包装成
     * {@code MultipartException}（不一定是它的子类 MaxUploadSizeExceededException），
     * 所以两者都要处理，否则会落到兜底分支变成 500。缺 file part 同理。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ApiResponse<Void> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        return ApiResponse.fail(400, "文件过大，单个课件不能超过 50MB");
    }

    @ExceptionHandler(MultipartException.class)
    public ApiResponse<Void> handleMultipart(MultipartException ex) {
        log.warn("multipart_invalid reason={}", ex.getMessage());
        return ApiResponse.fail(400, "上传请求不合法：文件缺失或超过大小限制");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ApiResponse<Void> handleMissingPart(MissingServletRequestPartException ex) {
        return ApiResponse.fail(400, "缺少上传的文件");
    }

    /**
     * 路径不存在。Spring 6.1+ 会把未匹配到 Controller 的请求交给静态资源处理器，
     * 抛 NoResourceFoundException；若不单独处理会被下面的兜底分支变成 500 并打 ERROR 堆栈。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(404, "接口不存在"));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.fail(401, "用户名或密码错误"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail(403, "无权访问"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        // 记录完整堆栈便于排查，但不把内部细节返回给调用方
        log.error("unexpected_error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(500, "服务器内部错误"));
    }
}
