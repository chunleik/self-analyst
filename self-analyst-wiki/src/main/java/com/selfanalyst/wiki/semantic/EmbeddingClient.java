package com.selfanalyst.wiki.semantic;

import java.util.List;

@FunctionalInterface
public interface EmbeddingClient {

    List<float[]> embed(List<String> texts);

    default float[] embedSingle(String text) {
        List<float[]> results = embed(List.of(text));
        if (results.isEmpty()) {
            throw new RuntimeException("Embedding returned empty result");
        }
        return results.get(0);
    }
}
