package com.selfanalyst.events.query;

import com.selfanalyst.events.store.EventStore;
import com.selfanalyst.events.store.BucketStore;

public record AqlContext(EventStore eventStore, BucketStore bucketStore,
                         String timeperiodStart, String timeperiodEnd) {}
