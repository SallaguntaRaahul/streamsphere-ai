package ai.streamsphere.common.web;

import org.springframework.http.HttpStatus;

/** Business/API error rendered as application/problem+json. {@code type} is a short slug, e.g. "quota-exhausted". */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String type;

    public ApiException(HttpStatus status, String type, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
    }

    public HttpStatus status() { return status; }
    public String type() { return type; }

    public static ApiException notFound(String what) { return new ApiException(HttpStatus.NOT_FOUND, "not-found", what + " not found"); }
    public static ApiException forbidden(String detail) { return new ApiException(HttpStatus.FORBIDDEN, "forbidden", detail); }
    public static ApiException conflict(String type, String detail) { return new ApiException(HttpStatus.CONFLICT, type, detail); }
    public static ApiException badRequest(String type, String detail) { return new ApiException(HttpStatus.BAD_REQUEST, type, detail); }
}
