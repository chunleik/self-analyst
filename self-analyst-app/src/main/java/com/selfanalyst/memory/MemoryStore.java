package com.selfanalyst.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
        MAPPER.writeValue(filePath.toFile(), profile);
    }

    public GrowthProfile profile() {
        return profile;
    }
}
