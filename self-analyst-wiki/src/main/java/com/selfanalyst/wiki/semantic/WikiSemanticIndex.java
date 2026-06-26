package com.selfanalyst.wiki.semantic;

import com.selfanalyst.wiki.WikiLevel;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class WikiSemanticIndex implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WikiSemanticIndex.class);
    private static final String FIELD_DOC_ID = "doc_id";
    private static final String FIELD_ENTRY_ID = "entry_id";
    private static final String FIELD_DOC_TYPE = "doc_type";
    private static final String FIELD_LEVEL = "level";
    private static final String FIELD_PERIOD_START = "period_start";
    private static final String FIELD_PERIOD_END = "period_end";
    private static final String FIELD_PERIOD_START_MS = "period_start_ms";
    private static final String FIELD_PERIOD_END_MS = "period_end_ms";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_EMBEDDING = "embedding";
    private static final String FIELD_SUMMARY = "summary";
    private static final String FIELD_PRIMARY_TASK = "primary_task";
    private static final String FIELD_MATCHED_TEXT = "matched_text";

    private final Directory dir;
    private final StandardAnalyzer analyzer;
    private final int dimensions;

    public WikiSemanticIndex(Path indexPath, int dimensions) {
        this.dimensions = dimensions;
        try {
            Files.createDirectories(indexPath);
            this.dir = FSDirectory.open(indexPath);
            this.analyzer = new StandardAnalyzer();
        } catch (IOException e) {
            throw new RuntimeException("Failed to open Lucene index at " + indexPath, e);
        }
    }

    public void indexDocument(String docId, String entryId, String docType,
                               WikiLevel level, Instant periodStart, Instant periodEnd,
                               String text, String summary, String primaryTask,
                               String matchedText, float[] embedding) throws IOException {
        try (IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(analyzer))) {
            writer.updateDocument(new Term(FIELD_DOC_ID, docId), buildDoc(docId, entryId,
                    docType, level, periodStart, periodEnd, text, summary,
                    primaryTask, matchedText, embedding));
            writer.commit();
        }
    }

    private Document buildDoc(String docId, String entryId, String docType,
                               WikiLevel level, Instant periodStart, Instant periodEnd,
                               String text, String summary, String primaryTask,
                               String matchedText, float[] embedding) {
        Document doc = new Document();
        doc.add(new StringField(FIELD_DOC_ID, docId, Field.Store.YES));
        doc.add(new StringField(FIELD_ENTRY_ID, entryId, Field.Store.YES));
        doc.add(new StringField(FIELD_DOC_TYPE, docType, Field.Store.YES));
        doc.add(new StringField(FIELD_LEVEL, level.name(), Field.Store.YES));
        doc.add(new StringField(FIELD_PERIOD_START, periodStart.toString(), Field.Store.YES));
        doc.add(new StringField(FIELD_PERIOD_END, periodEnd.toString(), Field.Store.YES));
        doc.add(new LongPoint(FIELD_PERIOD_START_MS, periodStart.toEpochMilli()));
        doc.add(new LongPoint(FIELD_PERIOD_END_MS, periodEnd.toEpochMilli()));
        doc.add(new TextField(FIELD_TEXT, text, Field.Store.YES));
        doc.add(new StoredField(FIELD_SUMMARY, summary != null ? summary : ""));
        doc.add(new StoredField(FIELD_PRIMARY_TASK, primaryTask != null ? primaryTask : ""));
        doc.add(new StoredField(FIELD_MATCHED_TEXT, matchedText != null ? matchedText : ""));
        doc.add(new KnnFloatVectorField(FIELD_EMBEDDING, embedding, VectorSimilarityFunction.COSINE));
        return doc;
    }

    public List<SearchHit> search(float[] queryVector, int topK, Instant start, Instant end,
                                   WikiLevel level) throws IOException {
        try (DirectoryReader reader = DirectoryReader.open(dir)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            Query knnQuery = new KnnFloatVectorQuery(FIELD_EMBEDDING, queryVector, topK);

            BooleanQuery.Builder filterBuilder = new BooleanQuery.Builder();
            if (start != null && end != null) {
                filterBuilder.add(LongPoint.newRangeQuery(FIELD_PERIOD_START_MS,
                        start.toEpochMilli(), end.toEpochMilli()), BooleanClause.Occur.FILTER);
            }
            if (level != null) {
                filterBuilder.add(new TermQuery(new Term(FIELD_LEVEL, level.name())),
                        BooleanClause.Occur.FILTER);
            }

            Query finalQuery;
            if (filterBuilder.build().clauses().isEmpty()) {
                finalQuery = knnQuery;
            } else {
                BooleanQuery.Builder combined = new BooleanQuery.Builder();
                combined.add(knnQuery, BooleanClause.Occur.MUST);
                filterBuilder.build().clauses().forEach(c -> combined.add(c.query(), c.occur()));
                finalQuery = combined.build();
            }

            TopDocs topDocs = searcher.search(finalQuery, topK);
            List<SearchHit> hits = new ArrayList<>();
            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(sd.doc);
                hits.add(new SearchHit(
                        (float) sd.score,
                        doc.get(FIELD_DOC_TYPE),
                        doc.get(FIELD_ENTRY_ID),
                        doc.get(FIELD_LEVEL),
                        doc.get(FIELD_PERIOD_START),
                        doc.get(FIELD_PERIOD_END),
                        doc.get(FIELD_SUMMARY),
                        doc.get(FIELD_PRIMARY_TASK),
                        doc.get(FIELD_MATCHED_TEXT)));
            }
            return hits;
        }
    }

    public void deleteByEntry(String entryId) throws IOException {
        try (IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(analyzer))) {
            writer.deleteDocuments(new Term(FIELD_ENTRY_ID, entryId));
            writer.commit();
        }
    }

    public void rebuild(Iterable<Document> documents) throws IOException {
        try (IndexWriter writer = new IndexWriter(dir,
                new IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE))) {
            for (Document doc : documents) {
                writer.addDocument(doc);
            }
            writer.commit();
        }
    }

    @Override
    public void close() {
        try {
            dir.close();
        } catch (IOException e) {
            log.warn("Failed to close Lucene directory", e);
        }
    }

    public record SearchHit(
            float score,
            String docType,
            String entryId,
            String level,
            String periodStart,
            String periodEnd,
            String summary,
            String primaryTask,
            String matchedText) {}
}
