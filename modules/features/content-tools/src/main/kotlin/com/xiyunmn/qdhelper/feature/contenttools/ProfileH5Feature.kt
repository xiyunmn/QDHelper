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

/** Adapts the host's independently deployed H5 profile without replacing its navigation. */
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
            ProfileH5PageApplier.clear()
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
    private val lastResults = Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val polling = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    fun clear() {
        lastResults.clear()
        polling.clear()
    }

    private fun report(webView: Any, reason: String, raw: String?) {
        val result = ProfileH5Policy.summarizeResult(raw)
        if (lastResults.put(webView, result) != result) {
            FeatureHooks.i("Profile H5 injection result: reason=$reason, result=$result")
        }
    }

    private fun pollStatus(webView: Any) {
        val view = webView as? View ?: return
        if (polling.put(webView, true) == true) return
        // evaluateJavascript returns before asynchronous requests finish. Sample their outcome
        // a bounded number of times; this does not repeat requests or retain page payloads.
        listOf(1500L, 10000L, 24000L).forEach { delay ->
            val posted = view.postDelayed({
                if (polling[webView] == true && ProfileH5Policy.isProfileHomeUrl(CardPlazaWebViewAccess.urlOf(webView))) {
                    CardPlazaWebViewAccess.evaluate(webView, PROFILE_H5_STATUS_SCRIPT) { report(webView, "section-status", it) }
                }
                if (delay == 24000L) polling.remove(webView)
            }, delay)
            if (!posted) polling.remove(webView)
        }
    }

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
                    report(webView, reason, rawResult)
                    if (options.privacyRestore) pollStatus(webView)
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
    private const val RESULT_LIMIT = 600
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
        var stateKey = '__qdhProfileH5V3';
        var existing = window[stateKey];
        if (existing && typeof existing.setOptions === 'function') return existing.setOptions(options);
        var previous = window.__qdhProfileH5V2;
        if (previous && typeof previous.setOptions === 'function') previous.setOptions({avatarOriginal:false,userId:false,privacyRestore:false});

        var userIdElementId = 'qdhelper-profile-user-id-v3';
        var restoreContainerId = 'qdhelper-profile-restored-v3';
        var styleElementId = 'qdhelper-profile-style-v3';
        var currentOptions = options, scheduled = false, lastAction = null, headerRetryCount = 0;
        var headerUserInfo = null, headerAuthorInfo = null, headerAuthorLight = null, isMaster = false;
        var restoreGeneration = 0, restoreUserKey = '', restoreStatus = 'idle', privacyDisplayRecord = null;
        var mountedRestoreApps = [], sectionResults = {}, sectionStates = {}, renderedSections = {};
        var commonModule = null, runtimeModule = null, commonStatus = 'idle', runtimeStatus = 'idle';

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
          var label = 'userId：' + userId;
          if (injected.textContent !== label) injected.textContent = label;
          if (injected.getAttribute('data-user-id') !== userId) injected.setAttribute('data-user-id', userId);
          return true;
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
          try {
            var url = new URL(rawUrl, window.location.href);
            if (url.protocol !== 'https:' || url.hostname !== 'qdfepccdn.qidian.com') return '';
            if (!/^\/(h5|brave|jtest)\.if\.qidian\.com\/new\/js\/[^/]+\.js$/.test(url.pathname)) return '';
            return filePattern.test(url.pathname.split('/').pop()) ? url.href : '';
          } catch (error) { return ''; }
        }
        function publicationPrefix() {
          var scripts = document.querySelectorAll('script[type="module"][src]');
          for (var i = scripts.length - 1; i >= 0; i--) {
            var entry = trustedModuleUrl(scripts[i].src, /^index_virtual_[^/]+_entry\.js$/);
            if (entry) return new URL(entry).pathname.replace(/[^/]+$/, '');
          }
          return '/' + window.location.hostname + '/new/js/';
        }
        function loadedModuleUrl(filePattern) {
          var prefix = publicationPrefix(), links = document.querySelectorAll('link[href],script[src]');
          function match(raw) { var value = trustedModuleUrl(raw, filePattern); return value && new URL(value).pathname.indexOf(prefix) === 0 ? value : ''; }
          for (var j = links.length - 1; j >= 0; j--) { var linked = match(links[j].href || links[j].src || ''); if (linked) return linked; }
          var resources = performance.getEntriesByType('resource');
          for (var i = resources.length - 1; i >= 0; i--) { var loaded = match(resources[i].name); if (loaded) return loaded; }
          return '';
        }
        function withTimeout(promise, milliseconds) {
          return new Promise(function(resolve, reject) {
            var timer = setTimeout(function(){reject(new Error('timeout'));}, milliseconds);
            Promise.resolve(promise).then(function(value){clearTimeout(timer);resolve(value);},function(error){clearTimeout(timer);reject(error);});
          });
        }
        function loadModule(pattern, generation, attempt) {
          if (generation !== restoreGeneration) return Promise.reject(new Error('stale'));
          var url = loadedModuleUrl(pattern);
          if (url) return withTimeout(import(url), 8000);
          if (attempt >= 2) return Promise.reject(new Error('module_missing'));
          return new Promise(function(resolve){setTimeout(resolve, 750 * (attempt + 1));})
            .then(function(){return loadModule(pattern, generation, attempt + 1);});
        }
        function endpointFunction(module, endpoint) {
          var matches = Object.keys(module || {}).map(function(key){return module[key];}).filter(function(value){return typeof value === 'function' && String(value).indexOf(endpoint) >= 0;});
          return matches.length === 1 ? matches[0] : null;
        }
        function listLength(data, key) { return data && Array.isArray(data[key]) ? data[key].length : 0; }
        function totalOf(data, primary, fallbackList) { var total = Number(data && data[primary]); return Number.isFinite(total) && total > 0 ? total : listLength(data, fallbackList); }
        function namedComponent(module, name) {
          var values = Object.keys(module || {}).map(function(key){return module[key];}).filter(function(value){return value && typeof value === 'object' && value.__name === name;});
          return values.length === 1 ? values[0] : null;
        }
        function vueCreateApp(runtime) {
          if (runtime && typeof runtime.createApp === 'function') return runtime.createApp;
          var keys = Object.keys(runtime || {});
          for (var i = 0; i < keys.length; i++) { var value = runtime[keys[i]]; if (value && typeof value === 'object' && typeof value.createApp === 'function' && typeof value.defineComponent === 'function') return value.createApp; }
          return null;
        }
        function restorePrivacyBlock() {
          if (!privacyDisplayRecord || !privacyDisplayRecord.node) return;
          var node = privacyDisplayRecord.node;
          if (privacyDisplayRecord.style === null) node.removeAttribute('style'); else node.setAttribute('style', privacyDisplayRecord.style);
          privacyDisplayRecord = null;
        }
        function clearRestoredSections() {
          var apps = mountedRestoreApps.splice(0);
          for (var i = 0; i < apps.length; i++) try { apps[i].unmount(); } catch (error) {}
          renderedSections = {};
          var container = document.getElementById(restoreContainerId); if (container) container.remove();
          restorePrivacyBlock();
        }
        var sectionDefinitions = [
          {name:'book', component:'booklist', prop:'myBookList', list:'BookLists', count:'Count', title:'TA的书单', route:'book-list'},
          {name:'column', component:'column', prop:'column', list:'ColumnList', count:'Count', title:'TA的专栏', route:'column-list'},
          {name:'chapter', component:'chapter-review', prop:'chapterReview', list:'ChapterReviewList', count:'Count', title:'TA的本章说', route:'chapter-review-list'},
          {name:'circle', component:'circle-review', prop:'circleReview', list:'CircleReviewList', count:'Count', title:'TA的帖子', route:'circle-review-list'},
          {name:'audio', component:'audio-info', prop:'audioInfo', list:'DataList', count:'TotalCount', title:'TA的配音', route:'audio-list'},
          {name:'yueli', component:'yueli', prop:'yueli', title:'TA的阅历'}
        ];
        var restoreIdentity = null;
        function sectionData(definition) {
          if (definition.name !== 'book') return sectionResults[definition.name];
          var book = sectionResults['book-user'];
          return listLength(book,'BookLists') ? book : sectionResults['book-author'];
        }
        function sectionAvailable(definition, data) { return definition.name === 'yueli' ? Boolean(data && data.Url) : listLength(data, definition.list) > 0; }
        function fallbackCard(host, definition, data) {
          var url;
          if (definition.name === 'yueli') url = safeActionUrl(data.Url);
          else {
            var route = new URL('/new/user-center/' + definition.route, window.location.origin);
            ['userId','authorId','authorType'].forEach(function(key){if(restoreIdentity[key])route.searchParams.set(key,restoreIdentity[key]);});
            url = route.href;
          }
          if (!url) throw new Error('route_missing');
          var link = document.createElement('a'); link.href = url; link.className = 'qdhelper-profile-fallback';
          link.style.cssText = 'display:block;padding:16px;margin:8px 0;color:inherit;text-decoration:none;border-bottom:1px solid rgba(128,128,128,.18)';
          var title = document.createElement('strong'); title.textContent = definition.title; link.appendChild(title);
          var summary = document.createElement('div'); summary.style.cssText = 'margin-top:6px;font-size:13px;opacity:.7';
          summary.textContent = definition.name === 'yueli' ? data.Tips : '共 ' + totalOf(data,definition.count,definition.list) + ' 条 · 查看全部';
          link.appendChild(summary);
          link.addEventListener('click',function(event){if(!currentOptions.privacyRestore)return;event.preventDefault();openHostActionUrl(url);});
          host.appendChild(link);
        }
        function renderRestoredSections() {
          if (!currentOptions.privacyRestore || !restoreIdentity) return;
          var stats = document.querySelector('main.page .user-stats'); if (!stats || !stats.parentElement) return;
          var available = sectionDefinitions.filter(function(definition){return sectionAvailable(definition,sectionData(definition));});
          if (!available.length) return;
          var container = document.getElementById(restoreContainerId);
          if (!container || container.parentElement !== stats.parentElement) {
            clearRestoredSections(); container = document.createElement('div'); container.id = restoreContainerId; stats.insertAdjacentElement('afterend',container);
          }
          var createApp = vueCreateApp(runtimeModule);
          available.forEach(function(definition) {
            var data = sectionData(definition), component = namedComponent(commonModule, definition.component);
            var mode = createApp && component ? 'native' : 'link';
            var previous = renderedSections[definition.name];
            if (previous && previous.data === data && previous.mode === mode && previous.host.parentElement === container) return;
            if (previous) { if(previous.app)try{previous.app.unmount();}catch(error){} previous.host.remove(); }
            var host = document.createElement('div'), app = null;
            host.setAttribute('data-qdh-section', definition.name); container.appendChild(host);
            var props = Object.assign({isMaster:false}, restoreIdentity); props[definition.prop] = data;
            if (definition.name === 'yueli') props.yueliSwitch = 1;
            try {
              if (mode === 'native') {
                app = createApp(component, props); app.directive('track',{}); app.config.warnHandler=function(){};
                var mountError = false; app.config.errorHandler=function(){
                  mountError=true;sectionStates[definition.name]='render_error';
                  var record=renderedSections[definition.name];
                  if(record&&record.app===app){try{app.unmount();record.app=null;host.textContent='';fallbackCard(host,definition,data);sectionStates[definition.name]='link_render_error';}catch(ignored){sectionStates[definition.name]='render_error';}}
                };
                app.mount(host); if(mountError)throw new Error('render_error');
                mountedRestoreApps.push(app); sectionStates[definition.name] = 'mounted';
              } else { fallbackCard(host,definition,data); sectionStates[definition.name] = component ? 'link_no_runtime' : 'link_no_component'; }
            } catch (error) {
              if(app)try{app.unmount();}catch(ignored){} app=null; host.textContent='';
              try { fallbackCard(host,definition,data); sectionStates[definition.name]='link_render_error'; }
              catch(ignored){host.remove();sectionStates[definition.name]='render_error';return;}
            }
            renderedSections[definition.name] = {data:data,mode:mode,host:host,app:app};
          });
          // Keep a stable order without moving already-correct nodes on every MutationObserver scan.
          var cursor = container.firstElementChild;
          sectionDefinitions.forEach(function(definition){var record=renderedSections[definition.name];if(!record)return;if(record.host!==cursor)container.insertBefore(record.host,cursor);cursor=record.host.nextElementSibling;});
          var privacyBlock = stats.querySelector('.privacy-block');
          if (Object.keys(renderedSections).length && privacyBlock && (!privacyDisplayRecord || privacyDisplayRecord.node !== privacyBlock)) {
            restorePrivacyBlock(); privacyDisplayRecord={node:privacyBlock,style:privacyBlock.getAttribute('style')};privacyBlock.style.setProperty('display','none','important');
          }
        }
        function status() {
          return 'privacy=' + restoreStatus + ',common=' + commonStatus + ',runtime=' + runtimeStatus + ',' +
            Object.keys(sectionStates).sort().map(function(key){return key+':'+sectionStates[key];}).join('|');
        }
        function resetPrivacy() {
          if (restoreUserKey) restoreGeneration++;
          restoreUserKey='';restoreStatus='idle';restoreIdentity=null;sectionResults={};sectionStates={};clearRestoredSections();
        }
        function requestPrivacySections() {
          var userId=headerUserInfo&&positiveId(headerUserInfo.UserId),authorId=headerAuthorInfo&&positiveId(headerAuthorInfo.AuthorId),privacy=Number(headerUserInfo&&headerUserInfo.PrivacyStat||0);
          if (!currentOptions.privacyRestore || (userId && (isMaster || privacy === 0))) { resetPrivacy(); return; }
          if (!userId) { if(restoreUserKey)resetPrivacy();restoreStatus='waiting_header';return; }
          var userKey=userId+':'+authorId;
          if (restoreUserKey === userKey) { renderRestoredSections(); return; }
          resetPrivacy();restoreUserKey=userKey;restoreStatus='active';var generation=++restoreGeneration;
          restoreIdentity={userId:userId,authorId:authorId,authorType:new URL(window.location.href).searchParams.get('authorType')||'1'};
          commonStatus='loading';runtimeStatus='loading';commonModule=null;runtimeModule=null;
          function current(){return generation===restoreGeneration&&currentOptions.privacyRestore;}
          function complete(name,data,state){
            if(!current())return;sectionResults[name]=data;sectionStates[name]=state;
            try{renderRestoredSections();}catch(error){sectionStates[name]='render_error';}
          }
          var specs=[{name:'book-user',endpoint:'/argus/api/v1/booklist/getuserbooklist',args:[userId,1],list:'BookLists'},
            {name:'column',endpoint:'/argus/api/v1/column/getusercolumnlist',args:[userId,1],list:'ColumnList'},
            {name:'chapter',endpoint:'/argus/api/v1/chapterreview/getuserchapterreview',args:[userId,1],list:'ChapterReviewList'},
            {name:'circle',endpoint:'/argus/api/v2/circle/getusercirclereviewlist',args:[userId,1],list:'Items'},
            {name:'audio',endpoint:'/argus/api/v1/user/getuserpageinfoaudiolist',args:[userId,1],list:'DataList'}];
          if(authorId)specs.push({name:'book-author',endpoint:'/argus/api/v1/booklist/getyoutabooklist',args:[authorId,1],list:'BookLists'});
          specs.forEach(function(spec){sectionStates[spec.name]='waiting_api';});sectionStates.yueli='loading';
          // Independent source: a missing host JS module must not suppress this request.
          var controller=typeof AbortController==='function'?new AbortController():null;
          withTimeout(Promise.resolve().then(function(){return fetch('/majax/ReadExp/Index?userId='+encodeURIComponent(userId),{credentials:'include',headers:{Accept:'application/json'},signal:controller?controller.signal:undefined});})
            .then(function(response){if(!response.ok)throw new Error('http_error');return response.json();}),10000)
            .then(function(json){var days=Number(json&&json.data&&json.data.expInfo&&json.data.expInfo.days);complete('yueli',Number.isFinite(days)&&days>0?{Tips:'来到起点已经 '+days+' 天',Url:'https://h5.if.qidian.com/new/yueli/?_viewmode=0&from=personalpage&userId='+encodeURIComponent(userId)}:null,'empty');},
              function(error){if(controller)controller.abort();complete('yueli',null,error.message==='timeout'?'timeout':'request_error');});
          loadModule(/^modules_[^/]+_chunk\.js$/,generation,0).then(function(module){if(!current())return;runtimeModule=module;runtimeStatus=vueCreateApp(module)?'ready':'contract_missing';renderRestoredSections();},function(error){if(current()){runtimeStatus=error.message==='module_missing'?'missing':'load_error';renderRestoredSections();}});
          loadModule(/^page_common_user-center_[^/]+_chunk\.js$/,generation,0).then(function(module){
            if(!current())return;commonModule=module;commonStatus='ready';renderRestoredSections();
            specs.forEach(function(spec){
              try {
                var fn=endpointFunction(module,spec.endpoint);
                if(!fn){complete(spec.name,null,'api_missing');return;}
                sectionStates[spec.name]='loading';
                withTimeout(Promise.resolve().then(function(){return fn.apply(null,spec.args);}),10000).then(function(data){
                  if(!data||typeof data!=='object'||!Array.isArray(data[spec.list])){complete(spec.name,null,'response_mismatch');return;}
                  var count=data[spec.list].length;
                  if(spec.name==='circle')data={CircleReviewList:data.Items,Count:totalOf(data,'TotalCount','Items')};
                  complete(spec.name,data,count?'ready':'empty');
                },function(error){complete(spec.name,null,error&&error.message==='timeout'?'timeout':'request_error');});
              } catch(error){complete(spec.name,null,'api_error');}
            });
          },function(error){if(!current())return;commonStatus=error.message==='module_missing'?'missing':'load_error';specs.forEach(function(spec){complete(spec.name,null,'module_unavailable');});});
        }
        function scan() {
          var hasHeader=readHeaderModel();
          if(!hasHeader&&headerRetryCount<20){headerRetryCount++;setTimeout(scheduleScan,250);}else if(hasHeader)headerRetryCount=0;
          var applied=applyUserId();requestPrivacySections();return 'idVisible='+(applied?'1':'0')+','+status();
        }
        function scheduleScan() { if(scheduled)return;scheduled=true;window.requestAnimationFrame(function(){scheduled=false;scan();}); }
        function setOptions(nextOptions) { currentOptions=nextOptions;lastAction=null;return scan(); }

        ensureStyle(); document.addEventListener('touchstart',rememberAction,true); document.addEventListener('mousedown',rememberAction,true); document.addEventListener('click',openProtectedStat,true);
        var observer=new MutationObserver(scheduleScan); observer.observe(document.body||document.documentElement,{childList:true,subtree:true});
        window[stateKey]={setOptions:setOptions,target:actionTarget,status:status}; return setOptions(options);
      } catch (error) { return 'error:'+error; }
    })();
"""

private const val PROFILE_H5_ACTION_TARGET_SCRIPT = """
    (function() {
      var state = window.__qdhProfileH5V3;
      return state && typeof state.target === 'function' ? state.target() : null;
    })();
"""

private const val PROFILE_H5_STATUS_SCRIPT = """
    (function(){var state=window.__qdhProfileH5V3;return state&&state.status?state.status():"not-installed";})();
"""
