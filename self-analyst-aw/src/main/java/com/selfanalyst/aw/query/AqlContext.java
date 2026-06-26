package com.selfanalyst.aw.query;

import com.selfanalyst.aw.store.EventStore;
import com.selfanalyst.aw.store.BucketStore;

public record AqlContext(EventStore eventStore, BucketStore bucketStore,
                         String timeperiodStart, String timeperiodEnd) {}
