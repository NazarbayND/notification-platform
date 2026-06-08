package com.notificationplatform.application.provider;

public class ProviderTemporaryException extends RuntimeException {

    private final String errorCode;

    public ProviderTemporaryException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
