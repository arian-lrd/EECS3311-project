package ca.yorku.eecs3311.huntassist.application;

public final class AppException extends RuntimeException {
    public enum Code { INVALID, NOT_FOUND, REFUSED, UNAVAILABLE, CONFLICT, FAILED, INTERNAL, CANCELLED }

    private final Code code;

    public AppException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() { return code; }

    public static AppException invalid(String message) { return new AppException(Code.INVALID, message); }
    public static AppException notFound(String message) { return new AppException(Code.NOT_FOUND, message); }
    public static AppException refused(String message) { return new AppException(Code.REFUSED, message); }
    public static AppException unavailable(String message) { return new AppException(Code.UNAVAILABLE, message); }
    public static AppException conflict(String message) { return new AppException(Code.CONFLICT, message); }
}
