package ai.streamsphere.common.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Only registered when spring-tx is on the classpath (not in the gateway). */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DataProblemAdvice {
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> optimistic(OptimisticLockingFailureException e) {
        return ProblemDetailsAdvice.problem(HttpStatus.CONFLICT, "concurrent-modification", "Resource was modified concurrently; reload and retry");
    }
}
