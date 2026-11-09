package com.selfanalyst.integrations.policy;

import com.selfanalyst.config.LlmSettings;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Recipient identity excludes credentials and model ids, but includes the actual endpoint path. */
public final class ModelRecipient {
    private ModelRecipient() {}
    public static String from(LlmSettings settings) {
        return normalize(settings.requestBaseUrl());
    }
    public static String normalize(String target) {
        try {
            URI uri = URI.create(target).normalize();
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (!Set.of("http", "https").contains(scheme) || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null)
                throw new IllegalArgumentException();
            int port = uri.getPort();
            if (scheme.equals("https") && port == 443 || scheme.equals("http") && port == 80) port = -1;
            // Preserve escaped separators: /tenant%2Fother and /tenant/other can route to different recipients.
            String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
            String authority = new URI(scheme, null, host, port, null, null, null).toASCIIString();
            return URI.create(authority + path).toASCIIString();
        } catch (Exception failure) {
            throw new IllegalArgumentException("MAIL_MODEL_TARGET_INVALID");
        }
    }
}
