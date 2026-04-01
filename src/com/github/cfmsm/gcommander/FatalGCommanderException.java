package com.github.cfmsm.gcommander;

public class FatalGCommanderException extends RuntimeException {
    public FatalGCommanderException(String message) {
        super(message);
        exit();
    }
    private void exit() {
        if (GCommander.quitOnFatalError) {
            System.err.println(getMessage() != null ? getMessage() : "FatalGCommanderError thrown, exiting program.");
            System.exit(1);
        }
    }
}