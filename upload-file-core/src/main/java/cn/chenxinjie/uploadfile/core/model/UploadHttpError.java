/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.model;

/**
 * Standard machine-readable failure body (rc.6, opt-in {@code http.error-body=standard}).
 *
 * <p>Carries a stable symbolic {@code code} from the {@link UploadErrorCodes} catalog together
 * with the HTTP status and optional request context (identifier/action). Integrations that
 * prefer their own envelope render their own body via the {@code UploadErrorRenderer} SPI
 * instead of using this model.</p>
 */
public class UploadHttpError {

    private String code;
    private int status;
    private String message;
    private String identifier;
    private String action;

    public static UploadHttpError of(String code, int status, String message, String identifier, String action) {
        UploadHttpError e = new UploadHttpError();
        e.setCode(code);
        e.setStatus(status);
        e.setMessage(message);
        e.setIdentifier(identifier);
        e.setAction(action);
        return e;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }
}
