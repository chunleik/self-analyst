package com.selfanalyst.aw.raw;

/** catalog 中原始事件月度分区的生命周期状态。 */
public enum RawPartitionStatus {
    ACTIVE,
    SEALING,
    SEALED,
    QUARANTINED
}
