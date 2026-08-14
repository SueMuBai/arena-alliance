package com.arena.alliance.common;

/**
 * 业务异常：httpStatus 直接映射响应码，message 面向用户展示。
 */
public class AllianceException extends RuntimeException {

    private final int status;

    public AllianceException(int status, String message) {
        super(message);
        this.status = status;
    }

    public static AllianceException badRequest(String message) {
        return new AllianceException(400, message);
    }

    public static AllianceException unauthorized(String message) {
        return new AllianceException(401, message);
    }

    public static AllianceException forbidden(String message) {
        return new AllianceException(403, message);
    }

    public static AllianceException notFound(String message) {
        return new AllianceException(404, message);
    }

    public int getStatus() {
        return status;
    }
}
