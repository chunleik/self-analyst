package com.selfanalyst;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppTest {

    @Test
    void publishesConfiguredDesktopPortAtomically(@TempDir Path dir) throws Exception {
        Path portFile = dir.resolve("backend-port.txt");

        App.publishDesktopPort(portFile, 45731);

        assertEquals("45731", Files.readString(portFile, StandardCharsets.UTF_8));
    }
}
