package com.xiyunmn.qdhelper.host.resolver.dexkit.query

import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier

internal object DeviceIdentityProbeQueries {
    private const val GETCONF_PATH = "argus/api/v1/client/getconf"

    val qdInfoCandidates = DexKitMethodQuery(
        id = "debug.device_identity_probe.qdinfo_candidates",
        purpose = "QDInfo plaintext/encrypted candidates with optional device-info flag",
        query = {
            searchPackages("com.qidian.common.lib")
            matcher {
                declaredClass(HostSymbols.Classes.COMMON_LIB_DEVICE)
                modifiers(Modifier.PUBLIC)
                addAnyOf { paramTypes("long", String::class.java.name) }
                addAnyOf { paramTypes("long", String::class.java.name, "boolean") }
                returnType(String::class.java)
            }
        },
        verify = HostMethodContracts::isQdInfoCandidate,
    )

    val commonDeviceIdentitySetters = DexKitMethodQuery(
        id = "debug.device_identity_probe.common_device_setters",
        purpose = "all common.lib.d qimei16/qimei36/OAID static String setters",
        query = {
            searchPackages("com.qidian.common.lib")
            matcher {
                declaredClass(HostSymbols.Classes.COMMON_LIB_DEVICE)
                modifiers(Modifier.PUBLIC or Modifier.STATIC)
                paramTypes(String::class.java.name)
                returnType(Void.TYPE)
            }
        },
        verify = HostMethodContracts::isCommonDeviceStringSetter,
    )

    val commonDeviceOstar16Setter = commonDeviceStringSetterQuery(
        id = "debug.device_identity_probe.ostar16_setter",
        purpose = "OStar qimei16 setter anchored by stable log text",
        anchor = "setOStarQIMEI16: qimei16 = ",
    )

    val commonDeviceOstar36Setter = commonDeviceStringSetterQuery(
        id = "debug.device_identity_probe.ostar36_setter",
        purpose = "OStar qimei36 setter anchored by stable log text",
        anchor = "setOStarQIMEI36: qimei36 = ",
    )

    val configLoaderRequest = DexKitMethodQuery(
        id = "debug.device_identity_probe.config_loader_request",
        purpose = "App config loader download request entry",
        query = {
            searchPackages("com.qidian.QDReader.component.config")
            matcher {
                paramCount(1)
                returnType(Void.TYPE)
                addUsingString("ConfigLoader", StringMatchType.Equals)
            }
        },
        verify = { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0].isInterface &&
                method.returnType == Void.TYPE &&
                HostInfrastructureContracts.isConfigLoader(method.declaringClass)
        },
    )

    val configLoaderApply = DexKitMethodQuery(
        id = "debug.device_identity_probe.config_loader_apply",
        purpose = "App config loader raw JSON apply/persist entry",
        query = {
            searchPackages("com.qidian.QDReader.component.config")
            matcher {
                paramCount(2)
                returnType(Void.TYPE)
            }
        },
        verify = { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == String::class.java &&
                method.parameterTypes[1].isInterface &&
                method.returnType == Void.TYPE &&
                HostInfrastructureContracts.isConfigLoader(method.declaringClass)
        },
    )

    val appConfigCacheLoaded = appConfigStageQuery(
        id = "debug.device_identity_probe.app_config_cache_loaded",
        purpose = "AppConfig cached file applied to runtime",
        anchor = "应用配置加载完成了",
    )

    val appConfigNetworkUpdated = appConfigStageQuery(
        id = "debug.device_identity_probe.app_config_network_updated",
        purpose = "AppConfig network response applied to runtime",
        anchor = "应用配置更新了",
    )

    val getconfApi = retrofitExactPathQuery(
        id = "debug.device_identity_probe.getconf_api",
        purpose = "exact Retrofit getconf API request",
        path = GETCONF_PATH,
    )

}
