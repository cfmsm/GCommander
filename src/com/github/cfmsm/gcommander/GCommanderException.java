package com.github.cfmsm.gcommander;

public class GCommanderException extends RuntimeException {
    public GCommanderException() {
        super();
    }
    public GCommanderException(String message) {
        super(message);
    }
    public GCommanderException(String message, Throwable cause) {
        super(message, cause);
    }
    public GCommanderException(Throwable cause) {
        super(cause);
    }
}
