package com.selfanalyst.aw.query;

import com.selfanalyst.aw.model.Event;
import java.util.List;
import java.util.Map;

@FunctionalInterface
public interface AqlFunction {
    List<Event> apply(List<Event> input, Map<String, Object> params, AqlContext ctx);
}
