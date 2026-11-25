package edu.pbl.gitviz.service.git;

/**
 * git 输出不符合预期格式。由 {@link GitRunner} 转成 {@link GitException.Kind#OUTPUT_UNREADABLE}。
 */
public final class GitOutputParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GitOutputParseException(String message) {
        super(message);
    }

    public GitOutputParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
