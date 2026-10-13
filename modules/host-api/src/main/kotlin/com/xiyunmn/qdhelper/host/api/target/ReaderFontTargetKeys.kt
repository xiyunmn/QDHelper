package com.xiyunmn.qdhelper.host.api.target

/** Stable reader-font host target contracts. */
object ReaderFontTargetKeys {
    val engineTypefaceGetter = TargetKey.MethodTarget(
        TargetId("reader.font.engine_typeface_getter"),
        Cardinality.One,
    )

    val engineInstanceGetter = TargetKey.MethodTarget(TargetId("reader.font.engine_instance_getter"))
    val engineTypefaceReload = TargetKey.MethodTarget(TargetId("reader.font.engine_typeface_reload"))

    val eventBusGetter = TargetKey.MethodTarget(
        TargetId("reader_font.event_bus.getter"),
        Cardinality.One,
    )

    val menuEventHandler = TargetKey.MethodTarget(
        TargetId("reader_font.menu_event.handler"),
        Cardinality.One,
    )

    val globalRegularTypefaceGetter = TargetKey.MethodTarget(
        TargetId("reader.font.global_regular_typeface_getter"),
        Cardinality.One,
    )

    val globalEmphasisTypefaceGetter = TargetKey.MethodTarget(
        TargetId("reader.font.global_emphasis_typeface_getter"),
        Cardinality.One,
    )

    val flutterNativeFontRegistrar = TargetKey.MethodTarget(
        TargetId("reader.font.flutter_native_font_registrar"),
        Cardinality.One,
    )

    val reader: Set<TargetKey.MethodTarget> = linkedSetOf(
        engineTypefaceGetter,
        engineInstanceGetter,
        engineTypefaceReload,
        eventBusGetter,
        menuEventHandler,
    )

    val global: Set<TargetKey.MethodTarget> = linkedSetOf(
        globalRegularTypefaceGetter,
        globalEmphasisTypefaceGetter,
        flutterNativeFontRegistrar,
    )

    val all: Set<TargetKey.MethodTarget> = reader + global
}
