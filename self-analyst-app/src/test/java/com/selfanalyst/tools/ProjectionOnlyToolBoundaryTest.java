package com.selfanalyst.tools;

import com.selfanalyst.wiki.WikiTools;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ProjectionOnlyToolBoundaryTest {

    @Test
    void agentToolsExposeNoRawEventQuery() {
        for (Class<?> toolClass : new Class<?>[]{EventQueryTools.class, WikiTools.class}) {
            assertFalse(Arrays.stream(toolClass.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName)
                    .anyMatch(name -> name.toLowerCase().contains("raw")), toolClass.getName());
        }
    }
}
