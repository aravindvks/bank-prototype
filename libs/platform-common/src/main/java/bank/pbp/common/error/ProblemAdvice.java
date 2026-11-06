package bank.pbp.common.error;

import bank.pbp.common.correlation.Correlation;
import java.net.URI;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Error envelope from LLD section 1: problem+json with code, correlationId and retryable. */
@RestControllerAdvice
public class ProblemAdvice {

    private static final Logger log = LoggerFactory.getLogger(ProblemAdvice.class);
    private static final String TYPE_BASE = "https://errors.pbp.bank/";

    @ExceptionHandler(PbpException.class)
    public ProblemDetail handle(PbpException ex) {
        log.warn("error code={} status={} detail={}", ex.code(), ex.status(), ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(ex.status()), ex.getMessage());
        pd.setTitle(ex.title());
        pd.setType(URI.create(TYPE_BASE + ex.code().toLowerCase()));
        return decorate(pd, ex.code(), ex.retryable());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handle(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setTitle("Request is not valid");
        pd.setType(URI.create(TYPE_BASE + "gen-valid-001"));
        return decorate(pd, "GEN-VALID-001", false);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handle(MissingRequestHeaderException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Header " + ex.getHeaderName() + " is required");
        pd.setTitle("Required header missing");
        pd.setType(URI.create(TYPE_BASE + "gen-header-001"));
        return decorate(pd, "GEN-HEADER-001", false);
    }

    private ProblemDetail decorate(ProblemDetail pd, String code, boolean retryable) {
        pd.setProperty("code", code);
        pd.setProperty("correlationId", Correlation.current().orElse(null));
        pd.setProperty("retryable", retryable);
        return pd;
    }
}
