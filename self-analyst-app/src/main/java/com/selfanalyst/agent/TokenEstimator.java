package com.selfanalyst.agent;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;

/**
 * Local token counting backed by jtokkit (tiktoken cl100k_base), replacing the
 * former {@code (len + 2) / 3} character heuristic. cl100k_base is a good
 * approximation for OpenAI gpt-4o/gpt-4/3.5 and most compatible providers; for
 * non-OpenAI models it remains a closer estimate than the char heuristic.
 * Falls back to the old heuristic if the encoding fails to initialize (e.g.
 * corrupted jar resources), so callers never break.
 */
public final class TokenEstimator {

    private static final EncodingRegistry REGISTRY = Encodings.newLazyEncodingRegistry();
    private static volatile Encoding encoding;

    private TokenEstimator() {}

    /** Estimated token count for {@code text}; 0 for null/empty. */
    public static long estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        Encoding enc = encoding();
        if (enc != null) {
            return enc.countTokens(text);
        }
        // Fallback: previous char-based heuristic.
        return (text.length() + 2) / 3;
    }

    private static Encoding encoding() {
        Encoding e = encoding;
        if (e == null) {
            synchronized (TokenEstimator.class) {
                e = encoding;
                if (e == null) {
                    try {
                        e = REGISTRY.getEncoding(EncodingType.CL100K_BASE);
                    } catch (RuntimeException ex) {
                        e = null; // stay on heuristic fallback
                    }
                    encoding = e;
                }
            }
        }
        return e;
    }
}
