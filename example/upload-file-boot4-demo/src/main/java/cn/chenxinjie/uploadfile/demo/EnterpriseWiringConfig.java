/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.demo;

import cn.chenxinjie.uploadfile.core.error.UploadErrorRenderer;
import cn.chenxinjie.uploadfile.core.security.AccessControl;
import cn.chenxinjie.uploadfile.core.security.AccessControlListener;
import cn.chenxinjie.uploadfile.core.security.AccessDecision;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * rc.6 commercial-wiring example, activated with {@code --spring.profiles.active=enterprise}.
 *
 * <p>Shows how a business system plugs its own authorization and audit into the component
 * (see the "migration guide" section of the README):</p>
 * <ul>
 *   <li>an {@link AccessControl} that overrides {@code decide(...)} and returns
 *       {@link AccessDecision#deny(int, String)} with {@code 403} on an owner mismatch (the new
 *       rc.6 API — no need to implement the deprecated {@code check(...)});</li>
 *   <li>an {@link AccessControlListener} that writes its own audit line on every decision.</li>
 * </ul>
 *
 * <p>For the demo, passing {@code ?token=deny-me} (or the configured header) triggers a {@code 403};
 * any other request is allowed. In a real system the token would be resolved from the caller's
 * session/owner instead. Run with:</p>
 *
 * <pre>{@code mvn -pl example/upload-file-boot4-demo spring-boot:run -Dspring-boot.run.profiles=enterprise}</pre>
 */
@Configuration
@Profile("enterprise")
public class EnterpriseWiringConfig {

    private static final Log AUDIT = LogFactory.getLog("upload-file.demo.audit");

    @Bean
    public AccessControl enterpriseAccessControl() {
        return new AccessControl() {
            @Override
            public AccessDecision decide(String identifier, String action, String token) {
                if ("deny-me".equals(token)) {
                    return AccessDecision.deny(403, "owner mismatch for " + identifier);
                }
                return AccessDecision.allow();
            }
        };
    }

    @Bean
    public AccessControlListener enterpriseAuditListener() {
        return (identifier, action, decision, elapsedNanos) -> AUDIT.info(
                "audit action=" + action + ", identifier=" + identifier + ", decision=" + decision
                        + ", elapsedMs=" + (elapsedNanos / 1_000_000.0));
    }

    /**
     * rc.8 (T50) unified-envelope example: a host {@link UploadErrorRenderer} bean wins over the
     * {@code http.error-body} property (rc.7), so every failure body is wrapped in the host's
     * {@link ApiResponse}. Success bodies are unchanged (the component endpoints still return their
     * own raw JSON) — only the failure body can be unified this way.
     */
    @Bean
    public UploadErrorRenderer enterpriseErrorRenderer() {
        return (action, identifier, status, code, message) -> ApiResponse.error(code, message, identifier);
    }

    /** A minimal business envelope: {@code {success, code, message, data}}. */
    public static final class ApiResponse {

        private final boolean success;
        private final String code;
        private final String message;
        private final Object data;

        private ApiResponse(boolean success, String code, String message, Object data) {
            this.success = success;
            this.code = code;
            this.message = message;
            this.data = data;
        }

        static ApiResponse error(String code, String message, Object data) {
            return new ApiResponse(false, code, message, data);
        }
    }
}
