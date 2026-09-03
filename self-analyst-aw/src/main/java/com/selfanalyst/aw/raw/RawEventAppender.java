package com.selfanalyst.aw.raw;

import java.util.List;

/** 业务写入链路可见的只追加原始事件接口。 */
public interface RawEventAppender {
    RawEvent append(RawEvent event);

    List<RawEvent> appendBatch(List<RawEvent> events);
}
