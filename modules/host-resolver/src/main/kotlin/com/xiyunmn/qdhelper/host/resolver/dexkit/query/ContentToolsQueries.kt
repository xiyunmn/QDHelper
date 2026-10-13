package com.xiyunmn.qdhelper.host.resolver.dexkit.query

import android.content.Context
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import org.json.JSONObject
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier

internal object ContentToolsQueries {
    val profileHomeResponseConvert = DexKitMethodQuery(
        id = "content_tools.profile_privacy.home_response_convert",
        purpose = "profile homepage response mapper deriving HomePageItem list from HomePageData",
        query = {
            searchPackages("com.qidian.QDReader.component.api")
            matcher {
                modifiers(Modifier.PUBLIC or Modifier.STATIC)
                paramTypes(
                    HostSymbols.Classes.SERVER_RESPONSE,
                    "boolean",
                )
                returnType(HostSymbols.Classes.SERVER_RESPONSE)
            }
        },
        verify = HostMethodContracts::isProfileHomeResponseConvert,
    )

    val profileChapterReviewsApi = DexKitMethodQuery(
        id = "content_tools.profile_privacy.chapter_reviews_api",
        purpose = "profile homepage chapter-review second-page API",
        query = {
            searchPackages("com.qidian.QDReader.component.api")
            matcher {
                modifiers(Modifier.PUBLIC or Modifier.STATIC)
                paramTypes(
                    Context::class.java.name,
                    "long",
                    "int",
                    null,
                )
                returnType(Void.TYPE)
                callerMethods {
                    add {
                        declaredClass(HostSymbols.Classes.HOME_PAGE_CHAPTER_REVIEWS_ACTIVITY)
                    }
                }
            }
        },
        verify = { method -> HostMethodContracts.isProfileSecondPageApi(method) },
    )

    val profileBookReviewsApi = DexKitMethodQuery(
        id = "content_tools.profile_privacy.book_reviews_api",
        purpose = "profile homepage book-review second-page API",
        query = {
            searchPackages("com.qidian.QDReader.component.api")
            matcher {
                modifiers(Modifier.PUBLIC or Modifier.STATIC)
                paramTypes(
                    Context::class.java.name,
                    "long",
                    "int",
                    null,
                )
                returnType(Void.TYPE)
                callerMethods {
                    add {
                        declaredClass(HostSymbols.Classes.HOME_PAGE_BOOK_REVIEWS_ACTIVITY)
                    }
                }
            }
        },
        verify = { method -> HostMethodContracts.isProfileSecondPageApi(method) },
    )

    val profilePreloadCallbackSuccess = DexKitMethodQuery(
        id = "content_tools.profile_privacy.preload_callback_success",
        purpose = "profile second-page preload callback implementation",
        // Several API callbacks share this wire format. The reflection contract narrows them by
        // enclosing-owner topology so no obfuscated owner, callback, or method name is persisted.
        // 7.9.476 moved this callback body behind a native stub, so method-body strings are no
        // longer available to DexKit even though the signature and class topology are unchanged.
        query = {
            searchPackages("com.qidian.QDReader.component.api")
            matcher {
                modifiers(Modifier.PUBLIC)
                paramTypes(
                    JSONObject::class.java.name,
                    String::class.java.name,
                    "int",
                )
                returnType(Void.TYPE)
            }
        },
        verify = HostMethodContracts::isProfilePreloadCallbackSuccess,
    )

    val profileColumnsApi = DexKitMethodQuery(
        id = "content_tools.profile_privacy.columns_api",
        purpose = "profile homepage columns second-page API",
        query = {
            searchPackages("com.qidian.QDReader.component.api")
            matcher {
                modifiers(Modifier.PUBLIC or Modifier.STATIC)
                paramTypes(
                    Context::class.java.name,
                    "long",
                    "int",
                    null,
                )
                returnType(Void.TYPE)
                callerMethods {
                    add {
                        declaredClass(HostSymbols.Classes.HOME_PAGE_COLUMNS_ACTIVITY)
                    }
                }
            }
        },
        verify = { method -> HostMethodContracts.isProfileSecondPageApi(method) },
    )

    val profileBookListApi = DexKitMethodQuery(
        id = "content_tools.profile_privacy.book_list_api",
        purpose = "profile homepage book-list second-page API",
        query = {
            searchPackages("com.qidian.QDReader.component.api")
            matcher {
                modifiers(Modifier.PUBLIC or Modifier.STATIC)
                paramTypes(
                    Context::class.java.name,
                    "long",
                    "int",
                    "long",
                    "boolean",
                    null,
                )
                returnType(Void.TYPE)
                callerMethods {
                    add {
                        declaredClass(HostSymbols.Classes.RECOM_BOOK_LIST_MORE_ACTIVITY)
                    }
                }
            }
        },
        verify = { method -> HostMethodContracts.isProfileBookListApi(method) },
    )

    val profileUserFollowApi = DexKitMethodQuery(
        id = "content_tools.profile_privacy.user_follow_api",
        purpose = "profile user follow (动态/TA的动态) feed API",
        query = {
            matcher {
                modifiers(Modifier.PUBLIC or Modifier.ABSTRACT)
                addAnnotation {
                    type("retrofit2.http.GET")
                    addElement {
                        name("value")
                        stringValue("/argus/api/v1/dynamic/getByUser", StringMatchType.Equals)
                    }
                }
                paramTypes("long", "int", "int")
                returnType("retrofit2.Call")
            }
        },
        verify = HostMethodContracts::isProfileUserFollowApi,
    )

    val profileUserAudioListApi = DexKitMethodQuery(
        id = "content_tools.profile_privacy.user_audio_list_api",
        purpose = "profile audio-list (配音) API",
        query = {
            matcher {
                modifiers(Modifier.PUBLIC or Modifier.ABSTRACT)
                addAnnotation {
                    type("retrofit2.http.GET")
                    addElement {
                        name("value")
                        stringValue("/argus/api/v1/user/getuserpageinfoaudiolist", StringMatchType.Equals)
                    }
                }
                paramTypes("long", "int", "int")
            }
        },
        verify = HostMethodContracts::isProfileUserAudioListApi,
    )

    val profileCapsuleApi = DexKitMethodQuery(
        id = "content_tools.profile_privacy.capsule_api",
        purpose = "profile capsule (胶囊) API",
        // The owner is an obfuscated top-level Retrofit interface (db.n in 7.9.470),
        // not necessarily under com.qidian.QDReader.component.api.  Keep the query
        // package-independent and let the exact wire path plus generic contract narrow it.
        query = {
            matcher {
                modifiers(Modifier.PUBLIC or Modifier.ABSTRACT)
                addAnnotation {
                    type("retrofit2.http.GET")
                    addElement {
                        name("value")
                        stringValue("/argus/api/v1/capsulenovel/createByUser", StringMatchType.Equals)
                    }
                }
                paramTypes("long", "int", "int")
                returnType(HostSymbols.Classes.RX_OBSERVABLE)
            }
        },
        verify = HostMethodContracts::isProfileCapsuleApi,
    )

    val profileCvApi = DexKitMethodQuery(
        id = "content_tools.profile_privacy.cv_api",
        purpose = "profile CV/配音 works second-page API",
        query = {
            matcher {
                modifiers(Modifier.PUBLIC or Modifier.ABSTRACT)
                addAnnotation {
                    type("retrofit2.http.GET")
                    addElement {
                        name("value")
                        stringValue("/argus/api/v1/audio/queryAnchorAudioPageList", StringMatchType.Equals)
                    }
                }
                paramTypes(String::class.java.name, String::class.java.name, null)
                returnType("java.lang.Object")
            }
        },
        verify = HostMethodContracts::isProfileCvApi,
    )
}
