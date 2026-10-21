package com.umi.nest.config;

import com.umi.nest.common.LoginRequiredException;
import com.umi.nest.common.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理静态资源 404（Spring Boot 3.x: favicon.ico 等）
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<?> handleNoResource(NoResourceFoundException e) {
        return Result.error("请求的资源不存在: " + e.getResourcePath());
    }

    /**
     * 处理 404 错误
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<?> handleNotFound(NoHandlerFoundException e) {
        return Result.error("请求的资源不存在: " + e.getRequestURL());
    }

    /**
     * 处理"未登录"。
     *
     * <p>必须排在兜底的 {@code Exception} 之前命中，否则会变成 500。
     * 状态码用 401、body 里的业务码也用 401——HTTP 状态和业务码表达同一件事时
     * 就不该给两个不同的数字，否则前端要维护两张对照表。
     *
     * <p>刻意<b>不</b>调 {@code e.printStackTrace()}：未登录是预期内的正常状态，
     * 打栈会把控制台淹掉，让真正的异常找不到。
     */
    @ExceptionHandler(LoginRequiredException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<?> handleLoginRequired(LoginRequiredException e) {
        return Result.error(401, e.getMessage());
    }

    /**
     * 处理请求参数非法。
     *
     * <p>没有这一条时，所有校验失败（「最多填 5 位」这种）都会落到兜底的 500，
     * 前端只能显示"服务器内部错误"——把"你填错了"和"代码崩了"混成同一件事，
     * 学生不知道该怎么办，运维也拿不到有效信号。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleBadRequest(IllegalArgumentException e) {
        return Result.error(400, e.getMessage());
    }

    /**
     * 处理「客户端把参数写坏了」这一类错误——路径/查询参数类型不对、JSON 体解析不了、必填参数没给。
     *
     * <p>没有这三条时它们全都落到兜底的 {@code Exception} → HTTP 500 + 一条
     * 「服务器内部错误: Failed to convert value of type 'java.lang.String' to required type
     * 'java.lang.Long'; For input string: "NaN"」——把「你请求写错了」伪装成「服务器崩了」，
     * 日志里真正的故障会被这类噪声淹掉。三条都按同一口径给 HTTP 400 + 业务码 400。</p>
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return Result.error(400, "参数格式不正确: " + e.getName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleUnreadableBody(HttpMessageNotReadableException e) {
        return Result.error(400, "请求体格式不正确（应为 JSON）");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleMissingParam(MissingServletRequestParameterException e) {
        return Result.error(400, "缺少必填参数: " + e.getParameterName());
    }

    /**
     * 处理所有未捕获的异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<?> handleException(Exception e) {
        e.printStackTrace();
        return Result.error("服务器内部错误: " + e.getMessage());
    }
}
