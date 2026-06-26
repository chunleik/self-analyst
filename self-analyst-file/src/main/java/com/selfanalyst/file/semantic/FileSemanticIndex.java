package com.selfanalyst.file.semantic;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Lucene KNN index over file summaries (SPEC-FILE-016).
 *
 * <p>A single {@link IndexWriter} is permitted at a time; {@code FileEmbeddingWorker}
 * is the only writer (SPEC-FILE-016a). Documents are keyed by {@code path} so
 * re-indexing a changed file replaces its previous vector.
 */
public class FileSemanticIndex implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FileSemanticIndex.class);

    private static final String FIELD_PATH = "path";
    private static final String FIELD_WATCH_ROOT = "watch_root";
    private static final String FIELD_EXTENSION = "extension";
    private static final String FIELD_LAST_MODIFIED_MS = "last_modified_ms";
    private static final String FIELD_SUMMARY = "summary";
    private static final String FIELD_MAIN_TOPICS = "main_topics";
    private static final String FIELD_FILE_HASH = "file_hash";
    private static final String FIELD_EMBEDDING = "embedding";

    private final Directory dir;
    private final StandardAnalyzer analyzer;

    public FileSemanticIndex(Path indexPath) {
        try {
            Files.createDirectories(indexPath);
            this.dir = FSDirectory.open(indexPath);
            this.analyzer = new StandardAnalyzer();
        } catch (IOException e) {
            throw new RuntimeException("Failed to open Lucene index at " + indexPath, e);
        }
    }

    public void indexDocument(String path, String watchRoot, String extension,
                              Instant lastModified, String summary, String mainTopics,
                              String fileHash, float[] embedding) throws IOException {
        try (IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(analyzer))) {
            writer.updateDocument(new Term(FIELD_PATH, path),
                    buildDoc(path, watchRoot, extension, lastModified, summary,
                            mainTopics, fileHash, embedding));
            writer.commit();
        }
    }

    public void deleteByPath(String path) throws IOException {
        try (IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(analyzer))) {
            writer.deleteDocuments(new Term(FIELD_PATH, path));
            writer.commit();
        }
    }

    /** True if a document for {@code path} with the same {@code fileHash} is already indexed. */
    public boolean isIndexed(String path, String fileHash) throws IOException {
        if (!DirectoryReader.indexExists(dir)) return false;
        try (DirectoryReader reader = DirectoryReader.open(dir)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            BooleanQuery query = new BooleanQuery.Builder()
                    .add(new TermQuery(new Term(FIELD_PATH, path)), BooleanClause.Occur.MUST)
                    .add(new TermQuery(new Term(FIELD_FILE_HASH, fileHash != null ? fileHash : "")),
                            BooleanClause.Occur.MUST)
                    .build();
            return searcher.count(query) > 0;
        }
    }

    public List<SearchHit> search(float[] queryVector, int topK, Instant start, Instant end,
                                  String watchRoot, String extension) throws IOException {
        if (!DirectoryReader.indexExists(dir)) return List.of();
        try (DirectoryReader reader = DirectoryReader.open(dir)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            Query knnQuery = new KnnFloatVectorQuery(FIELD_EMBEDDING, queryVector, topK);

            BooleanQuery.Builder filter = new BooleanQuery.Builder();
            boolean hasFilter = false;
            if (start != null && end != null) {
                filter.add(LongPoint.newRangeQuery(FIELD_LAST_MODIFIED_MS,
                        start.toEpochMilli(), end.toEpochMilli()), BooleanClause.Occur.FILTER);
                hasFilter = true;
            }
            if (watchRoot != null && !watchRoot.isBlank()) {
                filter.add(new TermQuery(new Term(FIELD_WATCH_ROOT, watchRoot)),
                        BooleanClause.Occur.FILTER);
                hasFilter = true;
            }
            if (extension != null && !extension.isBlank()) {
                filter.add(new TermQuery(new Term(FIELD_EXTENSION, extension.toLowerCase())),
                        BooleanClause.Occur.FILTER);
                hasFilter = true;
            }

            Query finalQuery;
            if (!hasFilter) {
                finalQuery = knnQuery;
            } else {
                BooleanQuery.Builder combined = new BooleanQuery.Builder();
                combined.add(knnQuery, BooleanClause.Occur.MUST);
                filter.build().clauses().forEach(c -> combined.add(c.query(), c.occur()));
                finalQuery = combined.build();
            }

            TopDocs topDocs = searcher.search(finalQuery, topK);
            List<SearchHit> hits = new ArrayList<>();
            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(sd.doc);
                hits.add(new SearchHit(
                        (float) sd.score,
                        doc.get(FIELD_PATH),
                        doc.get(FIELD_WATCH_ROOT),
                        doc.get(FIELD_EXTENSION),
                        doc.get(FIELD_SUMMARY),
                        doc.get(FIELD_MAIN_TOPICS)));
            }
            return hits;
        }
    }

    private Document buildDoc(String path, String watchRoot, String extension,
                             Instant lastModified, String summary, String mainTopics,
                             String fileHash, float[] embedding) {
        Document doc = new Document();
        doc.add(new StringField(FIELD_PATH, path, Field.Store.YES));
        doc.add(new StringField(FIELD_WATCH_ROOT, watchRoot != null ? watchRoot : "", Field.Store.YES));
        doc.add(new StringField(FIELD_EXTENSION,
                extension != null ? extension.toLowerCase() : "", Field.Store.YES));
        long ms = lastModified != null ? lastModified.toEpochMilli() : 0L;
        doc.add(new LongPoint(FIELD_LAST_MODIFIED_MS, ms));
        doc.add(new TextField(FIELD_SUMMARY, summary != null ? summary : "", Field.Store.YES));
        doc.add(new TextField(FIELD_MAIN_TOPICS, mainTopics != null ? mainTopics : "", Field.Store.YES));
        doc.add(new StringField(FIELD_FILE_HASH, fileHash != null ? fileHash : "", Field.Store.YES));
        doc.add(new KnnFloatVectorField(FIELD_EMBEDDING, embedding, VectorSimilarityFunction.COSINE));
        return doc;
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
            String path,
            String watchRoot,
            String extension,
            String summary,
            String mainTopics) {}
}
