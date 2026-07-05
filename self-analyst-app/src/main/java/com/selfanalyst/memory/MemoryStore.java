package com.selfanalyst.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class MemoryStore {

    private static final String FILE_NAME = "memory.json";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path filePath;
    private final GrowthProfile profile;

    private MemoryStore(Path filePath, GrowthProfile profile) {
        this.filePath = filePath;
        this.profile = profile;
    }

    public static MemoryStore load(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(FILE_NAME);
        GrowthProfile profile;
        if (Files.exists(file)) {
            profile = MAPPER.readValue(file.toFile(), GrowthProfile.class);
        } else {
            profile = new GrowthProfile();
        }
        return new MemoryStore(file, profile);
    }

    public void save() throws IOException {
        Files.createDirectories(filePath.getParent());
        Path temp = Files.createTempFile(filePath.getParent(), filePath.getFileName().toString(), ".tmp");
        boolean moved = false;
        try {
            MAPPER.writeValue(temp.toFile(), profile);
            try {
                Files.move(temp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, filePath, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }
    }

    public GrowthProfile profile() {
        return profile;
    }
}
