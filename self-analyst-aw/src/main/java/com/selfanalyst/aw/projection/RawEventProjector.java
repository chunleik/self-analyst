package com.selfanalyst.aw.projection;

import com.selfanalyst.aw.raw.RawEvent;

/** 写入入口依赖的最小投影接口，便于隔离 raw 与投影故障。 */
@FunctionalInterface
public interface RawEventProjector {
    EventProjector.ProjectionResult project(RawEvent event);
}
