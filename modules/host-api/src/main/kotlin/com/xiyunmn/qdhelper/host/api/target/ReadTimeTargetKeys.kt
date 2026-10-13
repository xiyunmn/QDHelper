package com.xiyunmn.qdhelper.host.api.target

/**
 * High-risk read-time targets used by the multiplier and its persistent local audit.
 */
object ReadTimeTargetKeys {
    /**
     * The single funnel where a finished reading session is persisted.
     *
     * Both host paths (reading body and listening/dubbing) converge here, and the host derives
     * the encrypted `EncodeReadTime` copy from `ReadTime` *inside* this method — so adjusting the
     * plaintext on entry keeps both representations consistent without touching the DES key.
     */
    val statisticsInsert = method("read_time.statistics.insert")

    /** Batch invoked from the host's network-success callback before rows become `Is_report=1`. */
    val statisticsMarkReported = method("read_time.statistics.mark_reported")

    /** Read-only cloud endpoint backing the host reading-time page, used to verify reporting. */
    val cloudReadPageApi = method("read_time.cloud.readpage_api")

    val all: Set<TargetKey.MethodTarget> = linkedSetOf(
        statisticsInsert,
        statisticsMarkReported,
        cloudReadPageApi,
    )

    private fun method(id: String) = TargetKey.MethodTarget(TargetId(id), Cardinality.One)
}
