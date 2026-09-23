package com.agentforge.controlplane.web;

import org.springframework.http.HttpStatus;

/** 等价于 FastAPI 的 HTTPException：带状态码和给前端看的中文说明。 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final Object detail;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
        this.detail = message;
    }

    public ApiException(HttpStatus status, Object detail, String message) {
        super(message);
        this.status = status;
        this.detail = detail;
    }

    public HttpStatus getStatus() { return status; }

    public Object getDetail() { return detail; }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    public static ApiException badGateway(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, message);
    }

    public static ApiException payloadTooLarge(String message) {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, message);
    }

    public static ApiException unsupportedMedia(String message) {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, message);
    }

    public static ApiException unavailable(String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
