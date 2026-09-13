package com.selfanalyst.document;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public final class DocumentTools {
    private final DocumentService service;
    public DocumentTools(DocumentService service) { this.service = service; }

    @Tool(name = "generate_document", description = "生成可保存的文档，支持 csv/json/markdown/xlsx/docx/pdf/pptx。"
            + "sourceJson 格式为 {schemaVersion:1,blocks:[{type:heading|paragraph|slide,text:文字},{type:list,items:[文字]},{type:table,table:{name:名称,columns:[列名],rows:[[值]]}}],sheets:[{name:名称,columns:[列名],rows:[[值]]}]}。"
            + "slide 仅用于 PPT；CSV 只接受一个 sheets，Excel 只接受 sheets。文字/表格均可编辑。限制：源1MiB、文件50MiB、PPT100页。修改请先读取已有生成源，并提供 parentArtifactId。成功后界面自动展示文件卡片，不编造本地路径。")
    public Mono<DocumentStore.Artifact> generate(
            @ToolParam(name = "format", description = "目标格式") String format,
            @ToolParam(name = "title", description = "文档标题，不是路径") String title,
            @ToolParam(name = "sourceJson", description = "schemaVersion=1 的结构化 JSON") String sourceJson,
            @ToolParam(name = "parentArtifactId", description = "修改已有文档时的文件 ID，新文档留空", required = false) String parent,
            DocumentExecution execution) {
        return Mono.fromCallable(() -> require(execution).run(() -> service.generate(execution.sessionId,
                execution.userMessageId, format, title, sourceJson, parent, execution::cancelled)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Tool(name = "read_document_source", description = "读取本会话已生成报告的有界结构，便于修改；数据导出只返回查询描述，不返回原始记录。")
    public Mono<String> read(
            @ToolParam(name = "artifactId", description = "本会话文件 ID") String id,
            DocumentExecution execution) {
        return Mono.fromCallable(() -> require(execution).run(() -> service.readSource(execution.sessionId, id)))
                .subscribeOn(Schedulers.boundedElastic());
    }
    private static DocumentExecution require(DocumentExecution execution) {
        if (execution == null) throw new IllegalArgumentException("当前聊天没有受管文档上下文");
        return execution;
    }

    public record Summary(String id, String name, String format, String status, int version, String parentId) {}

    @Tool(name = "list_documents", readOnly = true, description = "分页查看当前会话生成的文档名称、ID、格式与版本；上下文已压缩或不知道旧文件 ID 时先调用本工具。每页最多 50 项，不返回文档正文。")
    public Mono<java.util.Map<String, Object>> list(
            @ToolParam(name = "offset", description = "分页偏移，默认 0", required = false) Integer offset,
            DocumentExecution execution) {
        return Mono.fromCallable(() -> require(execution).run(() -> {
            int start = offset == null ? 0 : offset;
            var page = service.store().list(execution.sessionId, start, 50);
            var summaries = page.stream().map(a -> new Summary(a.id(), a.name(), a.format(), a.status(), a.version(), a.parentId())).toList();
            return java.util.Map.<String, Object>of("documents", summaries, "hasMore", page.size() == 50, "nextOffset", start + page.size());
        })).subscribeOn(Schedulers.boundedElastic());
    }

    @Tool(name = "export_data", description = "直接导出已保存记录为 csv/json/markdown/xlsx/docx/pdf/pptx，记录不经模型转写。"
            + "queryJson={source:raw|projection|file-metadata|wiki,bucketId:事件桶,start:ISO时间,end:ISO时间,timezone:Asia/Shanghai,fields:[字段],level:可选Wiki级别}。"
            + "时间范围开始包含结束不包含；raw 按 receivedAt，projection 按 timestamp，文件按 lastModified，Wiki 按时间块相交。"
            + "默认保留各来源字段；raw字段 eventId,bucketId,source,eventTimestamp,receivedAt,duration,data,schemaVersion；projection字段 id,timestamp,duration,data；"
            + "file-metadata字段 id,absolutePath,relativePath,watchRoot,extension,sizeBytes,lastModified,fileCreatedAt；wiki字段 id,level,periodStart,periodEnd,timezone,status,summary,primaryTask。"
            + "最多100000条，时间上限沿用本地查询配置，超限需缩小范围。")
    public Mono<DocumentStore.Artifact> export(
            @ToolParam(name = "format", description = "目标格式") String format,
            @ToolParam(name = "title", description = "文档标题") String title,
            @ToolParam(name = "queryJson", description = "有界数据查询条件 JSON") String queryJson,
            @ToolParam(name = "parentArtifactId", description = "前一版本 ID，可留空", required = false) String parent,
            DocumentExecution execution) {
        return Mono.fromCallable(() -> require(execution).run(() -> service.export(execution.sessionId,
                execution.userMessageId, format, title, queryJson, parent, execution::cancelled)))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
