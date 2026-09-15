package com.selfanalyst.agent;

import com.selfanalyst.desktop.store.ChatImageStore;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import reactor.core.publisher.Flux;
import java.nio.file.*;
import java.util.*;

/** Expands local references only in the transient model request, never in AgentState. */
final class ChatImageModel implements Model {
    private final Model delegate;
    private final Path root;
    ChatImageModel(Model delegate, Path memoryDir) {
        this.delegate = delegate;
        root = memoryDir.resolve("chat-images").toAbsolutePath().normalize();
    }
    @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.defer(() -> delegate.stream(messages.stream().map(this::expand).toList(), tools, options));
    }
    private Msg expand(Msg message) {
        return message.withContent(message.getContent().stream().map(block -> {
            if (!(block instanceof ImageBlock image)) return block;
            if (!(image.getSource() instanceof URLSource source)
                    || !source.getUrl().matches("selfanalyst-image:[a-f0-9]{32}/[a-f0-9]{32}"))
                throw new ImageUnavailableException();
            Path file = root.resolve(source.getUrl().substring(ChatImageStore.PREFIX.length()));
            try {
                if (Files.isSymbolicLink(file) || Files.isSymbolicLink(file.getParent())
                        || !file.toRealPath().startsWith(root.toRealPath())) throw new ImageUnavailableException();
                ChatImageStore.validate(file, source.getMimeType());
                return (ContentBlock) new ImageBlock(new Base64Source(source.getMimeType(),
                        Base64.getEncoder().encodeToString(Files.readAllBytes(file))));
            } catch (Exception failure) { throw new ImageUnavailableException(); }
        }).toList());
    }
    static List<ContentBlock> content(String text, String session, List<ChatImageStore.Image> images) {
        List<ContentBlock> content = new ArrayList<>();
        if (text != null && !text.isEmpty()) content.add(TextBlock.builder().text(text).build());
        if (images != null) for (var image : images)
            content.add(new ImageBlock(new URLSource(ChatImageStore.PREFIX + session + "/" + image.id(), image.mimeType())));
        if (content.isEmpty()) content.add(TextBlock.builder().text("").build());
        return content;
    }
    static List<Msg> textOnly(List<Msg> messages) {
        return messages.stream().map(m -> m.withContent(m.getContent().stream().map(b ->
                b instanceof ImageBlock ? (ContentBlock) TextBlock.builder().text("[User image]").build() : b).toList())).toList();
    }
    @Override public String getModelName() { return delegate.getModelName(); }
    @Override public boolean supportsNativeStructuredOutput() { return delegate.supportsNativeStructuredOutput(); }
    @Override public boolean supportsNativeStructuredOutputWithTools() { return delegate.supportsNativeStructuredOutputWithTools(); }
    @Override public int getContextWindowSize() { return delegate.getContextWindowSize(); }
    static final class ImageUnavailableException extends RuntimeException {
        ImageUnavailableException() { super("Chat image is missing or invalid"); }
    }
}
