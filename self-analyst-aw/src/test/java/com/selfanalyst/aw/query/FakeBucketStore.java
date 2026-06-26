package com.selfanalyst.aw.query;

import com.selfanalyst.aw.model.Bucket;
import com.selfanalyst.aw.store.BucketStore;

import java.util.List;

/**
 * Test double for BucketStore that returns a pre-configured list of buckets.
 */
class FakeBucketStore extends BucketStore {
    private final List<Bucket> buckets;

    FakeBucketStore(List<Bucket> buckets) {
        super(null);
        this.buckets = buckets;
    }

    @Override
    public List<Bucket> listAll() {
        return buckets;
    }
}
