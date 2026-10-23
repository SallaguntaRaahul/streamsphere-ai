package ai.streamsphere.common.storage;

import ai.streamsphere.common.web.ProblemDetailsAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import software.amazon.awssdk.core.exception.SdkClientException;

/** Object-store connectivity failures are a dependency outage (503, retryable), not a server bug (500). */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StorageProblemAdvice {
    private static final Logger log = LoggerFactory.getLogger(StorageProblemAdvice.class);

    @ExceptionHandler(SdkClientException.class)
    ResponseEntity<ProblemDetail> unavailable(SdkClientException e) {
        log.warn("Object storage unavailable: {}", e.getMessage());
        var r = ProblemDetailsAdvice.problem(HttpStatus.SERVICE_UNAVAILABLE, "storage-unavailable", "Media storage is temporarily unavailable; retry shortly");
        return ResponseEntity.status(r.getStatusCode()).headers(h -> { h.addAll(r.getHeaders()); h.set("Retry-After", "5"); }).body(r.getBody());
    }
}
