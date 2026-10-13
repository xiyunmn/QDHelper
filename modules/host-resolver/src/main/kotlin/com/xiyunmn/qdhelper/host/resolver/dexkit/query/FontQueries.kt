package com.xiyunmn.qdhelper.host.resolver.dexkit.query

import android.graphics.Typeface
import org.luckypray.dexkit.query.enums.StringMatchType

internal object FontQueries {
    val globalRegularTypefaceGetter = DexKitMethodQuery(
        id = "reader.font.global_regular_typeface_getter",
        purpose = "host application regular UI typeface getter (name-independent)",
        query = {
            matcher {
                paramCount(0)
                returnType(Typeface::class.java)
                addUsingString("SWITCH_SYSTEM_FONT", StringMatchType.Equals)
                addUsingNumber(3)
            }
        },
        verify = HostMethodContracts::isApplicationTypefaceGetter,
    )

    val globalEmphasisTypefaceGetter = DexKitMethodQuery(
        id = "reader.font.global_emphasis_typeface_getter",
        purpose = "host application emphasis UI typeface getter (name-independent)",
        query = {
            matcher {
                paramCount(0)
                returnType(Typeface::class.java)
                addUsingString("SWITCH_SYSTEM_FONT", StringMatchType.Equals)
                addUsingNumber(4)
            }
        },
        verify = HostMethodContracts::isApplicationTypefaceGetter,
    )

    val flutterNativeFontRegistrar = DexKitMethodQuery(
        id = "reader.font.flutter_native_font_registrar",
        purpose = "Flutter native-font BasicMessageChannel registrar (name-independent)",
        query = {
            matcher {
                paramCount(2)
                returnType(Void.TYPE)
                addUsingString("dev.flutter.pigeon.NativeFontApi.loadNativeFont", StringMatchType.Equals)
            }
        },
        verify = { method -> HostMethodContracts.isFlutterNativeFontRegistrar(method) },
    )
}
