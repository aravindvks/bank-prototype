package bank.pbp.common.error;

/**
 * Business or integration error with a stable code from LLD section 14
 * (for example PAY-LIMIT-001). Rendered as RFC 9457 problem+json.
 */
public class PbpException extends RuntimeException {

    private final String code;
    private final int status;
    private final String title;
    private final boolean retryable;

    public PbpException(String code, int status, String title, String detail, boolean retryable) {
        super(detail);
        this.code = code;
        this.status = status;
        this.title = title;
        this.retryable = retryable;
    }

    public PbpException(String code, int status, String title, String detail) {
        this(code, status, title, detail, false);
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public String title() {
        return title;
    }

    public boolean retryable() {
        return retryable;
    }
}
