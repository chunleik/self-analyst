package com.selfanalyst.aw.query;

import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.store.EventStore;

import java.util.List;

/**
 * Test double for EventStore that returns a pre-configured list of events.
 */
class FakeEventStore extends EventStore {
    private final List<Event> events;

    FakeEventStore(List<Event> events) {
        super(null, null);
        this.events = events;
    }

    @Override
    public List<Event> queryAllEvents(String bucketId) {
        return events;
    }
}
