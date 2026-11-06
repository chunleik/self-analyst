package com.selfanalyst.ontology;

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.util.Version;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class Neo4jLuceneCompatibilityTest {
    @Test
    void embeddedNeo4jKeepsItsLuceneNineCodec() {
        assertEquals(9, Version.LATEST.major,
                "Neo4j 5.26 test dependencies must not inherit the application Lucene major");
        Codec codec = assertDoesNotThrow(() -> Codec.forName("Lucene99"),
                "The embedded Neo4j database requires the Lucene99 codec");
        assertEquals("org.apache.lucene.codecs.lucene99.Lucene99Codec", codec.getClass().getName());
    }
}
