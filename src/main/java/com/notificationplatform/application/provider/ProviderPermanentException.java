package com.notificationplatform.application.provider;

public class ProviderPermanentException extends RuntimeException {

    private final String errorCode;

    public ProviderPermanentException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
