package com.selfanalyst.aw.query;

import java.util.Arrays;
import java.util.List;

public class AqlLexer {
    public List<String> tokenize(String aqlQuery) {
        return Arrays.stream(aqlQuery.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
