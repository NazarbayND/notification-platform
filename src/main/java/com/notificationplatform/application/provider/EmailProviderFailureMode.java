package com.notificationplatform.application.provider;

import java.util.Locale;

public enum EmailProviderFailureMode {
    SUCCESS,
    TEMPORARY_FAILURE_503,
    PERMANENT_FAILURE,
    TIMEOUT;

    public static EmailProviderFailureMode from(String value) {
        if (value == null || value.isBlank()) {
            return SUCCESS;
        }
        return EmailProviderFailureMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
