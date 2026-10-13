package com.xiyunmn.qdhelper.feature.contenttools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.Toast
import com.xiyunmn.qdhelper.capability.originalimage.OriginalImageDialog
import com.xiyunmn.qdhelper.capability.originalimage.OriginalImageLongPressBinder
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityLifecycleCallbacks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.shared.io.HttpTransport
import com.xiyunmn.qdhelper.shared.media.RemoteImageDownloader
import java.net.URI
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import org.json.JSONObject
import org.json.JSONTokener

/** Adapts the 7.9.476 H5 profile while leaving the host's H5 navigation in place. */
object ProfileH5Feature : InstallableFeature {
    override val name: String = "ProfileH5Feature"

    @Volatile
    private var activitySubscription: AutoCloseable? = null

    init {
        FeatureResources.register("profile-h5") {
            synchronized(this) {
                activitySubscription?.close()
                activitySubscription = null
            }
            ProfileH5LongPress.clear()
            ProfileH5WebViewRegistry.clear()
        }
    }

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        if (!HostSymbols.isMainProcess(processName)) return
        val pageFinished = installPageFinishedHook(module, classLoader)
        val lifecycle = installActivitySubscription()
        FeatureHooks.i(
            "Profile H5 phase finished: phase=$phase, process=$processName, " +
                "pageFinished=$pageFinished, lifecycle=$lifecycle",
        )
    }

    private fun installPageFinishedHook(module: HookModule, classLoader: ClassLoader): Boolean {
        val fragmentClass = FeatureHooks.findClassOrNull(HostSymbols.Classes.QD_BROWSER_FRAGMENT, classLoader)
            ?: return false
        val callback = FeatureHooks.allDeclaredMethods(fragmentClass).singleOrNull { method ->
            method.name == "onPageFinished" &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(arrayOf(String::class.java))
        } ?: return false
        return FeatureHooks.hookAfter(
            module = module,
            executable = callback,
            feature = "profile.h5:page finished",
            id = "qdhelper.profile.h5.page_finished",
        ) { chain, _ ->
            applyToOwner(chain.thisObject, chain.getArg(0) as? String, "page-finished")
        }
    }

    private fun installActivitySubscription(): Boolean = synchronized(this) {
        activitySubscription?.let { return@synchronized true }
        val registration = ActivityLifecycleCallbacks.subscribe(
            owner = OwnerId("content-tools"),
            id = "qdhelper.profile.h5.lifecycle",
            resumed = { activity ->
                if (activity.javaClass.name == HostSymbols.Classes.QD_BROWSER_ACTIVITY) {
                    activity.window?.decorView?.postDelayed(
                        { applyToOwner(activity, null, "activity-resumed") },
                        APPLY_AFTER_RESUME_DELAY_MS,
                    )
                }
            },
            windowFocusChanged = { activity, hasFocus ->
                if (hasFocus && activity.javaClass.name == HostSymbols.Classes.QD_BROWSER_ACTIVITY) {
                    activity.window?.decorView?.post {
                        applyToOwner(activity, null, "window-focused")
                    }
                }
            },
        ) ?: return@synchronized false
        activitySubscription = registration
        true
    }

    private fun applyToOwner(owner: Any?, expectedUrl: String?, reason: String) {
        owner ?: return
        val context = HostSessionStore.contextOrNull()
        val options = ProfileH5Options(
            avatarOriginal = SettingsRuntime.isProfileAvatarOriginalEnabled(context),
            userId = SettingsRuntime.isProfileUserIdEnabled(context),
            privacyRestore = SettingsRuntime.isProfilePrivacySecondPageRestoreEnabled(context),
        )
        ProfileH5PageApplier.apply(owner, expectedUrl, reason, options)
    }
}

internal object ProfileH5PageApplier {
    fun apply(owner: Any, expectedUrl: String?, reason: String, options: ProfileH5Options) {
        val webViews = CardPlazaWebViewAccess.webViewsOf(owner) + ProfileH5WebViewRegistry.snapshot()
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        webViews.filter(seen::add).forEach { webView ->
            val actualUrl = CardPlazaWebViewAccess.urlOf(webView)
            val url = actualUrl ?: expectedUrl
            if (!ProfileH5Policy.isProfileHomeUrl(url)) {
                ProfileH5LongPress.bind(webView, enabled = false)
                return@forEach
            }
            ProfileH5WebViewRegistry.register(webView)
            ProfileH5LongPress.bind(webView, options.avatarOriginal || options.userId)
            val queued = CardPlazaWebViewAccess.evaluate(
                webView = webView,
                script = ProfileH5Policy.script(options),
                onResult = { rawResult ->
                    FeatureHooks.i(
                        "Profile H5 injection result: reason=$reason, " +
                            "result=${ProfileH5Policy.summarizeResult(rawResult)}",
                    )
                },
            )
            if (!queued) FeatureHooks.w("Profile H5 injection unavailable: reason=$reason")
        }
    }
}

private const val APPLY_AFTER_RESUME_DELAY_MS = 500L

private object ProfileH5WebViewRegistry {
    private val views = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    fun register(webView: Any) {
        views[webView] = true
    }

    fun snapshot(): List<Any> = synchronized(views) { views.keys.toList() }

    fun clear() {
        views.clear()
    }
}

internal data class ProfileH5Options(
    val avatarOriginal: Boolean,
    val userId: Boolean,
    val privacyRestore: Boolean,
)

internal object ProfileH5Policy {
    private const val PROFILE_HOME_PATH = "/new/user-center/home"
    private const val AVATAR_TOKEN = "__QDHELPER_AVATAR_ENABLED__"
    private const val USER_ID_TOKEN = "__QDHELPER_USER_ID_ENABLED__"
    private const val PRIVACY_TOKEN = "__QDHELPER_PRIVACY_ENABLED__"
    private const val RESULT_LIMIT = 240
    private val allowedHosts = setOf(
        "h5.if.qidian.com",
        "brave.if.qidian.com",
        "jtest.if.qidian.com",
    )

    fun isProfileHomeUrl(rawUrl: String?): Boolean {
        val uri = rawUrl?.trim()?.takeIf(String::isNotEmpty)?.let { value ->
            runCatching { URI(value) }.getOrNull()
        } ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host?.lowercase() in allowedHosts &&
            uri.path.orEmpty().trimEnd('/') == PROFILE_HOME_PATH
    }

    fun script(options: ProfileH5Options): String = PROFILE_H5_SCRIPT_TEMPLATE
        .replace(AVATAR_TOKEN, options.avatarOriginal.toString())
        .replace(USER_ID_TOKEN, options.userId.toString())
        .replace(PRIVACY_TOKEN, options.privacyRestore.toString())

    fun summarizeResult(rawResult: String?): String = rawResult
        ?.trim()
        ?.removeSurrounding("\"")
        ?.replace("\\\"", "\"")
        ?.takeIf(String::isNotEmpty)
        ?.take(RESULT_LIMIT)
        ?: "no-callback"

    fun parseActionTarget(rawResult: String?): ProfileH5ActionTarget? {
        val first = rawResult?.takeIf(String::isNotBlank)?.let { raw ->
            runCatching { JSONTokener(raw).nextValue() }.getOrNull()
        } ?: return null
        val json = when (first) {
            is JSONObject -> first
            is String -> runCatching { JSONObject(first) }.getOrNull()
            else -> null
        } ?: return null
        return when (json.optString("kind")) {
            "avatar" -> {
                val url = HttpTransport.normalizeHttpUrl(json.optString("url")) ?: return null
                val uri = runCatching { URI(url) }.getOrNull() ?: return null
                if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
                ProfileH5ActionTarget.Avatar(
                    url = url,
                    displayName = json.optString("name").trim().take(80),
                    id = json.optString("id").trim().take(64),
                )
            }

            "userId" -> json.optString("value")
                .trim()
                .takeIf { it.matches(Regex("[1-9][0-9]{0,18}")) }
                ?.let(ProfileH5ActionTarget::UserId)

            else -> null
        }
    }
}

internal sealed interface ProfileH5ActionTarget {
    data class Avatar(
        val url: String,
        val displayName: String,
        val id: String,
    ) : ProfileH5ActionTarget

    data class UserId(val value: String) : ProfileH5ActionTarget
}

private object ProfileH5LongPress {
    private val boundViews = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>()),
    )

    fun bind(webView: Any, enabled: Boolean) {
        val view = webView as? View ?: return
        if (!enabled) {
            OriginalImageLongPressBinder.unregister(view)
            boundViews.remove(view)
            return
        }
        if (!boundViews.add(view)) return
        OriginalImageLongPressBinder.register(
            view = view,
            isEnabled = { context ->
                SettingsRuntime.isProfileAvatarOriginalEnabled(context) ||
                    SettingsRuntime.isProfileUserIdEnabled(context)
            },
            open = { context -> requestTarget(webView, view, context) },
        )
    }

    fun clear() {
        val views = synchronized(boundViews) { boundViews.toList().also { boundViews.clear() } }
        views.forEach(OriginalImageLongPressBinder::unregister)
    }

    private fun requestTarget(webView: Any, view: View, context: Context) {
        if (!ProfileH5Policy.isProfileHomeUrl(CardPlazaWebViewAccess.urlOf(webView))) return
        val queued = CardPlazaWebViewAccess.evaluate(
            webView = webView,
            script = PROFILE_H5_ACTION_TARGET_SCRIPT,
            onResult = { rawResult ->
                when (val target = ProfileH5Policy.parseActionTarget(rawResult)) {
                    is ProfileH5ActionTarget.Avatar -> {
                        if (!SettingsRuntime.isProfileAvatarOriginalEnabled(context)) return@evaluate
                        OriginalImageDialog.show(
                            context = context,
                            title = target.displayName.ifBlank { "头像原图" },
                            fileStem = "profile_avatar_h5_${target.id.ifBlank { "unknown" }}",
                        ) {
                            RemoteImageDownloader.download(target.url)
                        }
                    }

                    is ProfileH5ActionTarget.UserId -> {
                        if (!SettingsRuntime.isProfileUserIdEnabled(context)) return@evaluate
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("userId", target.value))
                        Toast.makeText(context, "已复制 userId", Toast.LENGTH_SHORT).show()
                    }

                    null -> Unit
                }
            },
        )
        if (!queued) {
            boundViews.remove(view)
            OriginalImageLongPressBinder.unregister(view)
            FeatureHooks.w("Profile H5 long-press target evaluation unavailable")
        }
    }
}

private const val PROFILE_H5_SCRIPT_TEMPLATE = """
    (function() {
      try {
        var path = String(window.location.pathname || '').replace(/\/+$/, '');
        var allowedHost = /^(h5|brave|jtest)\.if\.qidian\.com$/i.test(window.location.hostname);
        if (window.location.protocol !== 'https:' || !allowedHost || path !== '/new/user-center/home') return 'ignored';
        var options = {avatarOriginal: __QDHELPER_AVATAR_ENABLED__, userId: __QDHELPER_USER_ID_ENABLED__, privacyRestore: __QDHELPER_PRIVACY_ENABLED__};
        var stateKey = '__qdhProfileH5V2';
        var existing = window[stateKey];
        if (existing && typeof existing.setOptions === 'function') return existing.setOptions(options);

        var userIdElementId = 'qdhelper-profile-user-id-v1';
        var restoreContainerId = 'qdhelper-profile-restored-v1';
        var styleElementId = 'qdhelper-profile-style-v1';
        var currentOptions = options, scheduled = false, lastAction = null, headerRetryCount = 0;
        var headerUserInfo = null, headerAuthorInfo = null, headerAuthorLight = null, isMaster = false;
        var restoreGeneration = 0, restoreUserKey = '', restoreStatus = 'idle', privacyDisplayRecord = null;
        var mountedRestoreApps = [], cachedRestoreResults = null, cachedRestoreModules = null;

        function positiveId(value) {
          var text = String(value == null ? '' : value).trim();
          return /^[1-9][0-9]{0,18}$/.test(text) ? text : '';
        }
        function visitComponents(onInstance) {
          var appRoot = document.getElementById('app'), app = appRoot && appRoot.__vue_app__;
          var root = app && app._instance;
          if (!root && app && app._container && app._container._vnode) root = app._container._vnode.component;
          var visited = [];
          function visitInstance(instance) {
            if (!instance || visited.indexOf(instance) >= 0 || visited.length >= 400) return;
            visited.push(instance); onInstance(instance); visitVnode(instance.subTree);
          }
          function visitVnode(vnode) {
            if (!vnode) return;
            if (Array.isArray(vnode)) { for (var i = 0; i < vnode.length; i++) visitVnode(vnode[i]); return; }
            visitInstance(vnode.component); visitVnode(vnode.children); visitVnode(vnode.dynamicChildren);
            if (vnode.suspense) { visitVnode(vnode.suspense.activeBranch); visitVnode(vnode.suspense.pendingBranch); }
          }
          visitInstance(root);
        }
        function readHeaderModel() {
          headerUserInfo = null; headerAuthorInfo = null; headerAuthorLight = null; isMaster = false;
          visitComponents(function(instance) {
            var props = instance.props;
            if (!props || typeof props !== 'object') return;
            if (!headerUserInfo && props.userInfo && positiveId(props.userInfo.UserId)) { headerUserInfo = props.userInfo; isMaster = Boolean(props.isMaster); }
            if (!headerAuthorInfo && props.authorInfo && positiveId(props.authorInfo.AuthorId)) headerAuthorInfo = props.authorInfo;
            if (!headerAuthorLight && props.authorLight && typeof props.authorLight === 'object') headerAuthorLight = props.authorLight;
          });
          return Boolean(headerUserInfo);
        }
        function ensureStyle() {
          if (document.getElementById(styleElementId)) return;
          var style = document.createElement('style'); style.id = styleElementId;
          style.textContent = '#'+userIdElementId+'{margin:0;color:inherit;user-select:text;-webkit-user-select:text;}';
          (document.head || document.documentElement).appendChild(style);
        }
        function applyUserId() {
          var injected = document.getElementById(userIdElementId), userId = headerUserInfo && positiveId(headerUserInfo.UserId);
          if (!currentOptions.userId || !userId) { if (injected) injected.remove(); return false; }
          var stats = document.querySelector('main.page .user-stats'), description = stats && stats.querySelector('.desc');
          if (!description || !description.parentElement) return false;
          if (!injected) { injected = document.createElement('p'); injected.id = userIdElementId; injected.className = 'caption tc-surface-gray-500'; description.parentElement.insertBefore(injected, description); }
          injected.textContent = 'userId：' + userId; injected.setAttribute('data-user-id', userId); return true;
        }
        function avatarAt(event) {
          var point=event&&event.touches&&event.touches[0]||event, x=Number(point&&point.clientX), y=Number(point&&point.clientY); if(!Number.isFinite(x)||!Number.isFinite(y))return null;
          function candidate(selector,kind,model){var image=document.querySelector(selector);if(!image)return null;var rect=image.getBoundingClientRect();if(x<rect.left||x>rect.right||y<rect.top||y>rect.bottom)return null;return{image:image,kind:kind,model:model||{}};}
          return candidate('main.page section.author-info img.avatar-img','author',headerAuthorInfo)||candidate('main.page section.author-info img','author',headerAuthorInfo)||candidate('main.page section.user-stats img.avatar-img','user',headerUserInfo)||candidate('main.page section.user-stats img','user',headerUserInfo);
        }
        function rememberAction(event) {
          lastAction = null; var target = event && event.target; if (!target) return;
          var userIdNode = document.getElementById(userIdElementId);
          if (currentOptions.userId && userIdNode && (target === userIdNode || userIdNode.contains(target))) {
            var userId = positiveId(userIdNode.getAttribute('data-user-id')); if (userId) lastAction = {kind:'userId', value:userId, at:Date.now()}; return;
          }
          var avatarTarget=currentOptions.avatarOriginal&&avatarAt(event);if(!avatarTarget)return;var avatar=avatarTarget.image,model=avatarTarget.model,url=String(model.HeadImage||avatar.currentSrc||avatar.src||'').trim();
          if (/^\/\//.test(url)) url = 'https:' + url; if (!/^https?:\/\//i.test(url)) return;
          lastAction = {kind:'avatar', url:url, name:String(model.AuthorName || model.NickName || '头像原图'), id:positiveId(model.AuthorId || model.UserId), avatarKind:avatarTarget.kind, at:Date.now()};
        }
        function actionTarget() {
          if (!lastAction || Date.now() - lastAction.at > 2500) return null;
          var result = {}; Object.keys(lastAction).forEach(function(key) { if (key !== 'at') result[key] = lastAction[key]; }); return JSON.stringify(result);
        }
        function safeActionUrl(value){var url=String(value||'').trim();if(/^\/\//.test(url))url='https:'+url;return /^(https?:\/\/|QDReader:\/\/)/i.test(url)?url:'';}
        function openHostActionUrl(url){if(/^https?:\/\//i.test(url)&&window.jssdk&&jssdk.app&&typeof jssdk.app.open==='function'){jssdk.app.open(encodeURI(url));return;}window.location.href=url;}
        function openProtectedStat(event){
          if(!currentOptions.privacyRestore||isMaster||Number(headerUserInfo&&headerUserInfo.PrivacyStat||0)===0)return;var target=event&&event.target,button=target&&target.closest&&target.closest('main.page section.user-stats button.stat-item');if(!button)return;
          var label=button.querySelector('.caption'),text=String(label&&label.textContent||'').trim(),url='';
          if(text==='大神之光')url=safeActionUrl(headerUserInfo&&headerUserInfo.AuthorLightUrl||headerAuthorLight&&headerAuthorLight.AuthorLightUrl);
          if(text==='全订作品')url=safeActionUrl(headerUserInfo&&headerUserInfo.AllSubUrl)||('https://h5.if.qidian.com/new/yueli/fullAll/?_viewmode=0&from=personalpage&userId='+encodeURIComponent(positiveId(headerUserInfo&&headerUserInfo.UserId)));
          if(!url)return;event.preventDefault();event.stopPropagation();if(typeof event.stopImmediatePropagation==='function')event.stopImmediatePropagation();openHostActionUrl(url);
        }
        function trustedModuleUrl(rawUrl, filePattern) {
          try { var url = new URL(rawUrl, window.location.href); if (url.protocol !== 'https:' || url.hostname !== 'qdfepccdn.qidian.com') return '';
            if (!filePattern.test(url.pathname)) return ''; return url.href; } catch (error) { return ''; }
        }
        function loadedModuleUrl(filePattern) {
          var resources = performance.getEntriesByType('resource');
          for (var i = 0; i < resources.length; i++) { var trusted = trustedModuleUrl(resources[i].name,filePattern); if (trusted) return trusted; }
          var links = document.querySelectorAll('link[href$="_chunk.js"],script[src$="_chunk.js"]');
          for (var j = 0; j < links.length; j++) { var linked = trustedModuleUrl(links[j].href || links[j].src || '',filePattern); if (linked) return linked; }
          return '';
        }
        function endpointFunction(module, endpoint) {
          var keys = Object.keys(module); for (var i = 0; i < keys.length; i++) { var value = module[keys[i]]; if (typeof value === 'function' && String(value).indexOf(endpoint) >= 0) return value; } return null;
        }
        function listLength(data, key) { return data && Array.isArray(data[key]) ? data[key].length : 0; }
        function totalOf(data, primary, fallbackList) { var total = Number(data && data[primary]); return Number.isFinite(total) && total > 0 ? total : listLength(data, fallbackList); }
        function namedComponent(module, name) {
          var keys = Object.keys(module); for (var i = 0; i < keys.length; i++) { var value = module[keys[i]]; if (value && typeof value === 'object' && value.__name === name) return value; } return null;
        }
        function vueCreateApp(runtime) {
          var keys = Object.keys(runtime); for (var i = 0; i < keys.length; i++) { var value = runtime[keys[i]]; if (value && typeof value === 'object' && typeof value.createApp === 'function' && typeof value.defineComponent === 'function') return value.createApp; }
          return null;
        }
        function restorePrivacyBlock() {
          if (!privacyDisplayRecord || !privacyDisplayRecord.node) return; var node = privacyDisplayRecord.node;
          if (privacyDisplayRecord.style === null) node.removeAttribute('style'); else node.setAttribute('style', privacyDisplayRecord.style); privacyDisplayRecord = null;
        }
        function clearRestoredSections() {
          var apps=mountedRestoreApps.splice(0); for(var i=0;i<apps.length;i++)try{apps[i].unmount();}catch(error){}
          var container = document.getElementById(restoreContainerId); if (container) container.remove(); restorePrivacyBlock();
        }
        function renderRestoredSections(results, commonModule, runtimeModule) {
          clearRestoredSections(); if (!currentOptions.privacyRestore || !results || !results.length) return 0;
          var userBook = results.find(function(item){return item.name === 'book-user';}), authorBook = results.find(function(item){return item.name === 'book-author';});
          var book = userBook && listLength(userBook.data,'BookLists') ? userBook : authorBook, column = results.find(function(item){return item.name === 'column';}), chapter = results.find(function(item){return item.name === 'chapter';}), circle = results.find(function(item){return item.name === 'circle';}), audio = results.find(function(item){return item.name === 'audio';}), yueli = results.find(function(item){return item.name === 'yueli';});
          var createApp=vueCreateApp(runtimeModule), stats=document.querySelector('main.page .user-stats'); if(!createApp||!stats||!stats.parentElement)return 0;
          var container=document.createElement('div'); container.id=restoreContainerId; stats.insertAdjacentElement('afterend',container);
          var userId=headerUserInfo&&positiveId(headerUserInfo.UserId), authorId=headerAuthorInfo&&positiveId(headerAuthorInfo.AuthorId), authorType=new URL(window.location.href).searchParams.get('authorType')||'1', mounted=0;
          function mount(name,props){var component=namedComponent(commonModule,name);if(!component)return false;var host=document.createElement('div');container.appendChild(host);try{var app=createApp(component,props);app.directive('track',{});app.config.warnHandler=function(){};app.mount(host);mountedRestoreApps.push(app);mounted++;return true;}catch(error){host.remove();return false;}}
          var base={isMaster:false,userId:userId,authorId:authorId,authorType:authorType};
          if(book&&listLength(book.data,'BookLists'))mount('booklist',Object.assign({myBookList:book.data},base));
          if(column&&listLength(column.data,'ColumnList'))mount('column',Object.assign({column:column.data},base));
          if(chapter&&listLength(chapter.data,'ChapterReviewList'))mount('chapter-review',Object.assign({chapterReview:chapter.data},base));
          if(circle&&listLength(circle.data,'Items'))mount('circle-review',Object.assign({circleReview:{CircleReviewList:circle.data.Items,Count:totalOf(circle.data,'TotalCount','Items')}},base));
          if(audio&&listLength(audio.data,'DataList'))mount('audio-info',Object.assign({audioInfo:audio.data},base));
          var days=Number(yueli&&yueli.data&&yueli.data.expInfo&&yueli.data.expInfo.days);if(Number.isFinite(days)&&days>0)mount('yueli',{yueli:{Tips:'来到起点已经 '+days+' 天',Url:'https://h5.if.qidian.com/new/yueli/?_viewmode=0&from=personalpage&userId='+encodeURIComponent(userId)},yueliSwitch:1,isMaster:false});
          if(!mounted){container.remove();return 0;}
          var privacyBlock = stats.querySelector('.privacy-block'); if (privacyBlock) { privacyDisplayRecord = {node:privacyBlock,style:privacyBlock.getAttribute('style')}; privacyBlock.style.setProperty('display','none','important'); }
          return mounted;
        }
        function requestPrivacySections() {
          var userId = headerUserInfo && positiveId(headerUserInfo.UserId), authorId = headerAuthorInfo && positiveId(headerAuthorInfo.AuthorId), privacy = Number(headerUserInfo && headerUserInfo.PrivacyStat || 0);
          if (!currentOptions.privacyRestore) { restoreGeneration++; restoreUserKey=''; restoreStatus='idle'; cachedRestoreResults=null; cachedRestoreModules=null; clearRestoredSections(); return; }
          if (!userId) { restoreStatus=restoreStatus==='idle'?'waiting-header':restoreStatus; return; }
          if (isMaster || privacy === 0) { restoreGeneration++; restoreUserKey=''; restoreStatus='idle'; cachedRestoreResults=null; cachedRestoreModules=null; clearRestoredSections(); return; }
          var userKey = userId+':'+authorId;
          if(restoreUserKey===userKey&&restoreStatus==='loading')return;
          if(restoreUserKey===userKey&&restoreStatus.indexOf('done:')===0){if(document.getElementById(restoreContainerId))return;if(cachedRestoreResults&&cachedRestoreModules){var remounted=renderRestoredSections(cachedRestoreResults,cachedRestoreModules.common,cachedRestoreModules.runtime);restoreStatus='done:'+remounted;if(remounted)return;}}
          if(restoreUserKey&&restoreUserKey!==userKey){cachedRestoreResults=null;cachedRestoreModules=null;clearRestoredSections();}
          restoreUserKey=userKey; restoreStatus='loading'; var generation=++restoreGeneration;
          var commonUrl=loadedModuleUrl(/^\/h5\.if\.qidian\.com\/new\/js\/page_common_user-center_[^/]+_chunk\.js$/),runtimeUrl=loadedModuleUrl(/^\/h5\.if\.qidian\.com\/new\/js\/modules_[^/]+_chunk\.js$/); if (!commonUrl||!runtimeUrl) { restoreStatus='no-module'; return; }
          Promise.all([import(commonUrl),import(runtimeUrl)]).then(function(modules) {var module=modules[0];
            var specs=[{name:'book-user',endpoint:'/argus/api/v1/booklist/getuserbooklist',args:[userId,1]},{name:'column',endpoint:'/argus/api/v1/column/getusercolumnlist',args:[userId,1]},{name:'chapter',endpoint:'/argus/api/v1/chapterreview/getuserchapterreview',args:[userId,1]},{name:'circle',endpoint:'/argus/api/v2/circle/getusercirclereviewlist',args:[userId,1]},{name:'audio',endpoint:'/argus/api/v1/user/getuserpageinfoaudiolist',args:[userId,1]}];
            if (authorId) specs.push({name:'book-author',endpoint:'/argus/api/v1/booklist/getyoutabooklist',args:[authorId,1]});
            var requests=specs.map(function(spec){var fn=endpointFunction(module,spec.endpoint); if(!fn)return Promise.resolve({name:spec.name,data:null}); return Promise.resolve(fn.apply(null,spec.args)).then(function(data){return{name:spec.name,data:data||null};},function(){return{name:spec.name,data:null};});});
            requests.push(fetch('/majax/ReadExp/Index?userId='+encodeURIComponent(userId),{credentials:'include',headers:{Accept:'application/json'}}).then(function(response){return response.ok?response.json():null;}).then(function(json){return{name:'yueli',data:json&&json.data||null};},function(){return{name:'yueli',data:null};}));
            return Promise.all(requests).then(function(results){return{results:results,common:modules[0],runtime:modules[1]};});
          }).then(function(payload){if(generation!==restoreGeneration||!currentOptions.privacyRestore)return;cachedRestoreResults=payload.results;cachedRestoreModules={common:payload.common,runtime:payload.runtime};var count=renderRestoredSections(payload.results,payload.common,payload.runtime);restoreStatus='done:'+count;}).catch(function(){if(generation===restoreGeneration)restoreStatus='error';});
        }
        function scan() { var hasHeader=readHeaderModel();if(!hasHeader){if(headerRetryCount<20){headerRetryCount++;setTimeout(scheduleScan,250);}requestPrivacySections();return 'waiting:header,privacy='+restoreStatus;}headerRetryCount=0;var userIdApplied=applyUserId();requestPrivacySections();return 'ready:userId='+(userIdApplied?'1':'0')+',privacy='+restoreStatus; }
        function scheduleScan() { if(scheduled)return; scheduled=true; window.requestAnimationFrame(function(){scheduled=false;scan();}); }
        function setOptions(nextOptions) {
          currentOptions=nextOptions; lastAction=null; if(!currentOptions.userId)applyUserId();
          if(!currentOptions.privacyRestore){restoreGeneration++;restoreUserKey='';restoreStatus='idle';cachedRestoreResults=null;cachedRestoreModules=null;clearRestoredSections();}
          return scan();
        }
        ensureStyle(); document.addEventListener('touchstart',rememberAction,true); document.addEventListener('mousedown',rememberAction,true); document.addEventListener('click',openProtectedStat,true);
        var observer=new MutationObserver(scheduleScan); observer.observe(document.body||document.documentElement,{childList:true,subtree:true});
        window[stateKey]={setOptions:setOptions,target:actionTarget}; return setOptions(options);
      } catch (error) { return 'error:'+error; }
    })();
"""

private const val PROFILE_H5_ACTION_TARGET_SCRIPT = """
    (function() {
      var state = window.__qdhProfileH5V2;
      return state && typeof state.target === 'function' ? state.target() : null;
    })();
"""
