package com.selfanalyst.content.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Circular buffer of the 240 most recent OCR screenshots.
 *
 * Writes are submitted to a single background thread with a bounded queue;
 * if the writer falls behind the queue is silently dropped so the OCR
 * capture thread never blocks on I/O.
 *
 * File layout (under {@link #dir}):
 *   slot_00.png / slot_00.json  …  slot_49.png / slot_49.json
 *
 * Configure the directory via env var OCR_SAMPLE_DIR or system property
 * ocr.sample.dir; defaults to ~/.selfanalyst/ocr-samples.
 */
public class OcrSampleStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OcrSampleStore.class);
    static final int CAPACITY = 240;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path dir;
    // Single writer thread; bounded queue prevents unbounded memory growth.
    // DiscardPolicy drops new tasks when full — capture thread is never blocked.
    private final ThreadPoolExecutor writer;
    private final AtomicInteger nextSlot = new AtomicInteger(0);

    public OcrSampleStore(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
        this.writer = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4),
            new ThreadPoolExecutor.DiscardPolicy());
        log.info("OcrSampleStore ready: {} (capacity={})", dir, CAPACITY);
    }

    /**
     * Create a store at the configured directory, or return null if the
     * directory cannot be created (feature is silently disabled).
     */
    public static OcrSampleStore createDefault() {
        String envDir = System.getenv("OCR_SAMPLE_DIR");
        if (envDir == null) envDir = System.getProperty("ocr.sample.dir");
        Path dir = (envDir != null && !envDir.isBlank())
            ? Path.of(envDir)
            : Path.of(System.getProperty("user.home"), ".selfanalyst", "ocr-samples");
        try {
            return new OcrSampleStore(dir);
        } catch (IOException e) {
            log.warn("OcrSampleStore disabled — cannot create {}: {}", dir, e.getMessage());
            return null;
        }
    }

    /**
     * Non-blocking: enqueues a save task for the given image and metadata.
     * {@code sampleId} is the UUID that also appears in the AW heartbeat event,
     * enabling exact row-level lookup in the ActivityWatch database.
     * Silently dropped if the writer queue is full.
     */
    public void submit(BufferedImage image, String app, String title,
                       String ocrText, int uiaChars, String sampleId, long ocrMs) {
        if (image == null) return;
        int slot = nextSlot.getAndUpdate(i -> (i + 1) % CAPACITY);
        Instant ts = Instant.now();
        // image is read-only after capture; safe to hand off without copying
        writer.execute(() -> save(slot, image, app, title, ocrText, uiaChars, sampleId, ts, ocrMs));
    }

    private void save(int slot, BufferedImage image, String app, String title,
                      String ocrText, int uiaChars, String sampleId, Instant ts, long ocrMs) {
        String prefix = String.format("slot_%02d", slot);
        Path imgPath = dir.resolve(prefix + ".png");
        Path metaPath = dir.resolve(prefix + ".json");
        try {
            ImageIO.write(image, "PNG", imgPath.toFile());

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("slot", slot);
            meta.put("sample_id", sampleId != null ? sampleId : "");
            meta.put("timestamp", ts.toString());
            meta.put("app", app != null ? app : "");
            meta.put("title", title != null ? title : "");
            meta.put("uia_chars", uiaChars);
            meta.put("ocr_chars", ocrText != null ? ocrText.length() : 0);
            meta.put("ocr_ms", ocrMs);
            meta.put("ocr_text", ocrText != null ? ocrText : "");
            MAPPER.writeValue(metaPath.toFile(), meta);
        } catch (IOException e) {
            log.warn("Failed to write OCR sample slot {}: {}", slot, e.getMessage());
        }
    }

    /** Directory where samples are stored. */
    public Path getDir() {
        return dir;
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            writer.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
