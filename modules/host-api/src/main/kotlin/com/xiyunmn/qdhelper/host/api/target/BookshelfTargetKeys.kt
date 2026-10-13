package com.xiyunmn.qdhelper.host.api.target

/** Stable bookshelf-domain host target contracts. */
object BookshelfTargetKeys {
    val appConfigCurrent = method("bookshelf.config.app_config_current")
    val newListBookBind = method("ui.bookshelf.new_list_book.bind")
    val defaultTopBackgroundLoad = method("ui.bookshelf.top_background.default_load")
    val operationParseLambda = method("ui.bookshelf.operation.parse_lambda")
    val operationNotifyLambda13_12 = method("ui.bookshelf.operation.notify.lambda_13_12")
    val operationNotifyLambda0 = method("ui.bookshelf.operation.notify.lambda_0")
    val topOperationEventHandler = method("bookshelf.top_operation.event_handler")
    val nativeNetBridgeRegister = method("ui.daily_reading.native_net_bridge.register")

    val all: Set<TargetKey.MethodTarget> = linkedSetOf(
        appConfigCurrent,
        newListBookBind,
        defaultTopBackgroundLoad,
        operationParseLambda,
        operationNotifyLambda13_12,
        operationNotifyLambda0,
        topOperationEventHandler,
        nativeNetBridgeRegister,
    )

    private fun method(id: String) = TargetKey.MethodTarget(TargetId(id), Cardinality.One)
}
