package com.selfanalyst.document;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** 有界磁盘行集；内存只保留偏移，渲染器按需读取当前行。 */
final class DocumentRowSpool extends AbstractList<List<JsonNode>> implements AutoCloseable {
    private final Path path;
    private final FileChannel channel;
    private final ArrayList<Long> offsets = new ArrayList<>();
    DocumentRowSpool(Path path) throws IOException {
        this.path = path;
        channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }
    void append(List<JsonNode> values) {
        try {
            byte[] bytes = DocumentRequest.JSON.writeValueAsBytes(values);
            long position = channel.size();
            if (offsets.size() >= DocumentRequest.MAX_ROWS || position + bytes.length + 4 > DocumentBudget.MAX_FILE_BYTES)
                throw new IllegalArgumentException("导出临时数据超过预算，请缩小范围");
            offsets.add(position);
            ByteBuffer buffer = ByteBuffer.allocate(4 + bytes.length).putInt(bytes.length).put(bytes); buffer.flip();
            while (buffer.hasRemaining()) channel.write(buffer);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    @Override public List<JsonNode> get(int index) {
        Objects.checkIndex(index, offsets.size());
        try {
            long offset = offsets.get(index);
            ByteBuffer length = ByteBuffer.allocate(4); readFully(length, offset); length.flip();
            ByteBuffer bytes = ByteBuffer.allocate(length.getInt()); readFully(bytes, offset + 4);
            JsonNode row = DocumentRequest.JSON.readTree(bytes.array());
            var values = new ArrayList<JsonNode>(); row.forEach(values::add); return values;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private void readFully(ByteBuffer buffer, long offset) throws IOException {
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer, offset + buffer.position());
            if (count < 0) throw new IOException("导出临时数据不完整");
        }
    }
    @Override public int size() { return offsets.size(); }
    @Override public void close() throws IOException { try { channel.close(); } finally { Files.deleteIfExists(path); } }
}
