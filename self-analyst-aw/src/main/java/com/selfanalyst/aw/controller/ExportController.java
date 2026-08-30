package com.selfanalyst.aw.controller;

import com.selfanalyst.aw.export.DataExporter;
import com.selfanalyst.aw.export.DataImporter;
import com.selfanalyst.aw.store.BucketStore;
import com.selfanalyst.aw.store.EventStore;
import com.selfanalyst.aw.store.ContentEventPolicyViolationException;
import io.javalin.http.Context;

import java.util.Map;

public class ExportController {

    private final DataExporter exporter;
    private final DataImporter importer;

    public ExportController(BucketStore bucketStore, EventStore eventStore) {
        this.exporter = new DataExporter(bucketStore, eventStore);
        this.importer = new DataImporter(bucketStore, eventStore);
    }

    public void exportAll(Context ctx) {
        try {
            Map<String, Object> data = exporter.exportAll();
            ctx.json(data);
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to export data: " + e.getMessage()));
        }
    }

    public void importAll(Context ctx) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = ctx.bodyAsClass(Map.class);
            Map<String, Object> result = importer.importData(data);
            ctx.json(result);
        } catch (ContentEventPolicyViolationException e) {
            ctx.status(422).json(Map.of(
                    "error", "Imported content event violates persisted-field policy",
                    "field", e.field()));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to import data: " + e.getMessage()));
        }
    }

    public void exportBucket(Context ctx) {
        try {
            String bucketId = ctx.pathParam("id");
            Map<String, Object> data = exporter.exportBucket(bucketId);
            ctx.json(data);
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to export bucket: " + e.getMessage()));
        }
    }
}
