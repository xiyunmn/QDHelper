package com.xiyunmn.qdhelper.feature.contenttools

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityLifecycleCallbacks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.URI
import java.util.Collections
import java.util.IdentityHashMap

object CardPlazaUnlockFeature : InstallableFeature {
    override val name: String = "CardPlazaUnlockFeature"

    @Volatile
    private var activitySubscription: AutoCloseable? = null

    init {
        FeatureResources.register("card-plaza-local-unlock") {
            synchronized(this) {
                activitySubscription?.close()
                activitySubscription = null
            }
            CardPreviewImageSaver.clear()
        }
    }

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        if (!HostSymbols.isMainProcess(processName)) return
        val pageFinishedHook = installPageFinishedHook(module, classLoader)
        val lifecycle = installActivitySubscription()
        FeatureHooks.i(
            "Card plaza local unlock phase finished: phase=$phase, process=$processName, " +
                "pageFinished=$pageFinishedHook, lifecycle=$lifecycle",
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
            feature = "contentTools.cardPlazaUnlock:page finished",
            id = "qdhelper.card_plaza.local_unlock.page_finished",
        ) { chain, _ ->
            val url = chain.getArg(0) as? String
            if (CardPlazaUnlockPolicy.isCardPlazaUrl(url) || CardPreviewPolicy.isPreviewUrl(url)) {
                applyToOwner(chain.thisObject, url, reason = "page-finished")
            }
        }
    }

    private fun installActivitySubscription(): Boolean = synchronized(this) {
        activitySubscription?.let { return@synchronized true }
        val registration = ActivityLifecycleCallbacks.subscribe(
            owner = OwnerId("content-tools"),
            id = "qdhelper.card_plaza.local_unlock.resume",
            resumed = { activity ->
                if (CardPlazaUnlockPolicy.isSupportedBrowserActivity(activity.javaClass.name)) {
                    activity.window?.decorView?.post {
                        applyToOwner(activity, expectedUrl = null, reason = "activity-resumed")
                    }
                }
            },
            windowFocusChanged = { activity, hasFocus ->
                if (hasFocus && CardPlazaUnlockPolicy.isSupportedBrowserActivity(activity.javaClass.name)) {
                    activity.window?.decorView?.post {
                        applyToOwner(activity, expectedUrl = null, reason = "window-focused")
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
        val unlockEnabled = SettingsRuntime.isCardPlazaLocalUnlockEnabled(context)
        val saveEnabled = SettingsRuntime.isCardPreviewLongPressSaveEnabled(context)
        CardPlazaWebViewAccess.webViewsOf(owner).forEach { webView ->
            val url = expectedUrl ?: CardPlazaWebViewAccess.urlOf(webView)
            when {
                CardPlazaUnlockPolicy.isCardPlazaUrl(url) -> {
                    CardPreviewImageSaver.bind(webView, enabled = false)
                    injectScript(
                        webView = webView,
                        script = CardPlazaUnlockPolicy.script(unlockEnabled),
                        feature = "Card plaza local unlock",
                        reason = reason,
                        resultSummary = CardPlazaUnlockPolicy::summarizeResult,
                    )
                }

                CardPreviewPolicy.isPreviewUrl(url) -> {
                    injectScript(
                        webView = webView,
                        script = CardPreviewPolicy.unlockScript(unlockEnabled),
                        feature = "Card preview local unlock",
                        reason = reason,
                        resultSummary = CardPlazaUnlockPolicy::summarizeResult,
                    )
                    injectScript(
                        webView = webView,
                        script = CardPreviewPolicy.saveBridgeScript(saveEnabled),
                        feature = "Card preview long-press save bridge",
                        reason = reason,
                        resultSummary = CardPlazaUnlockPolicy::summarizeResult,
                    )
                    CardPreviewImageSaver.bind(webView, saveEnabled)
                }

                else -> CardPreviewImageSaver.bind(webView, enabled = false)
            }
        }
    }

    private fun injectScript(
        webView: Any,
        script: String,
        feature: String,
        reason: String,
        resultSummary: (String?) -> String,
    ) {
        if (
            !CardPlazaWebViewAccess.evaluate(
                webView = webView,
                script = script,
                onResult = { result ->
                    FeatureHooks.i(
                        "$feature injection result: reason=$reason, result=${resultSummary(result)}",
                    )
                },
            )
        ) {
            FeatureHooks.w("$feature injection unavailable: reason=$reason")
        }
    }
}

/**
 * The plaza derives ownership presentation and, in some host H5 revisions, preview eligibility from
 * the Vue card model's HasNum field. Keep network responses untouched, temporarily update that local
 * model, and restore it when the switch is disabled.
 */
internal object CardPlazaUnlockPolicy {
    private const val HOST = "h5.if.qidian.com"
    private const val PATH = "/h5/roleCard/cardPlaza"
    private const val SCRIPT_ENABLED_TOKEN = "__QDHELPER_ENABLED__"
    private const val RESULT_LIMIT = 160

    fun isCardPlazaUrl(rawUrl: String?): Boolean {
        val uri = rawUrl?.trim()?.takeIf(String::isNotEmpty)?.let { value ->
            runCatching { URI(value) }.getOrNull()
        } ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (!uri.host.equals(HOST, ignoreCase = true)) return false
        return uri.path.orEmpty().trimEnd('/') == PATH
    }

    fun isSupportedBrowserActivity(className: String): Boolean =
        className == HostSymbols.Classes.QD_BROWSER_ACTIVITY ||
            className == HostSymbols.Classes.TAB_BROWSER_ACTIVITY

    fun script(enabled: Boolean): String = CARD_PLAZA_UNLOCK_SCRIPT_TEMPLATE.replace(
        SCRIPT_ENABLED_TOKEN,
        enabled.toString(),
    )

    fun summarizeResult(rawResult: String?): String {
        val normalized = rawResult
            ?.trim()
            ?.removeSurrounding("\"")
            ?.replace("\\\"", "\"")
            ?.takeIf(String::isNotEmpty)
            ?: "no-callback"
        return normalized.take(RESULT_LIMIT)
    }
}

internal object CardPlazaWebViewAccess {
    fun webViewsOf(owner: Any): List<Any> {
        val candidates = mutableListOf<Any>()
        owner.javaClass.methods
            .firstOrNull(::isWebViewGetter)
            ?.invokeSafely(owner)
            ?.let(candidates::add)

        if (owner is Activity) {
            collectWebViews(owner.window?.decorView, candidates)
        } else {
            owner.javaClass.methods
                .firstOrNull { method ->
                    method.name == "getView" &&
                        method.parameterCount == 0 &&
                        View::class.java.isAssignableFrom(method.returnType)
                }
                ?.invokeSafely(owner)
                ?.let { view -> collectWebViews(view as? View, candidates) }
        }

        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        return candidates.filter { candidate -> isWebViewLike(candidate) && seen.add(candidate) }
    }

    fun urlOf(webView: Any): String? = webView.javaClass.methods
        .firstOrNull { method ->
            method.name == "getUrl" &&
                method.parameterCount == 0 &&
                method.returnType == String::class.java
        }
        ?.invokeSafely(webView) as? String

    fun evaluate(webView: Any, script: String, onResult: (String?) -> Unit): Boolean {
        val method = webView.javaClass.methods.firstOrNull { candidate ->
            candidate.name == "evaluateJavascript" &&
            candidate.returnType == Void.TYPE &&
            candidate.parameterTypes.size == 2 &&
                candidate.parameterTypes[0] == String::class.java &&
                !candidate.parameterTypes[1].isPrimitive
        } ?: return false
        val callback = callbackProxy(method.parameterTypes[1], onResult)
        return runCatching { method.invoke(webView, script, callback) }.isSuccess
    }

    private fun collectWebViews(root: View?, candidates: MutableList<Any>) {
        root ?: return
        if (isWebViewLike(root)) candidates += root
        if (root !is ViewGroup) return
        for (index in 0 until root.childCount) {
            collectWebViews(root.getChildAt(index), candidates)
        }
    }

    private fun isWebViewLike(candidate: Any): Boolean = candidate.javaClass.methods.any { method ->
        method.name == "evaluateJavascript" &&
            method.returnType == Void.TYPE &&
            method.parameterTypes.size == 2 &&
            method.parameterTypes[0] == String::class.java
    }

    private fun callbackProxy(type: Class<*>, onResult: (String?) -> Unit): Any? {
        if (!type.isInterface) return null
        return runCatching {
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "onReceiveValue" -> {
                        onResult(args?.firstOrNull()?.toString())
                        null
                    }
                    "toString" -> "CardPlazaJavascriptCallback"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            }
        }.getOrNull()
    }

    private fun isWebViewGetter(method: Method): Boolean =
        method.name == "getWebView" &&
            method.parameterCount == 0 &&
            method.returnType != Void.TYPE

    private fun Method.invokeSafely(receiver: Any): Any? = runCatching { invoke(receiver) }.getOrNull()
}

private const val CARD_PLAZA_UNLOCK_SCRIPT_TEMPLATE = """
    (function() {
      try {
        var path = String(window.location.pathname || '');
        while (path.length > 1 && path.charAt(path.length - 1) === '/') {
          path = path.slice(0, -1);
        }
        if (window.location.hostname !== 'h5.if.qidian.com' || path !== '/h5/roleCard/cardPlaza') {
          return 'ignored';
        }

        var enabled = __QDHELPER_ENABLED__;
        var stateKey = '__qdhCardPlazaUnlockV2';
        var existing = window[stateKey];
        if (existing && typeof existing.setEnabled === 'function') {
          return existing.setEnabled(enabled);
        }

        var legacy = window.__qdhCardPlazaUnlockV1;
        if (legacy && typeof legacy.setEnabled === 'function') {
          try { legacy.setEnabled(false); } catch (ignored) {}
        }

        var itemMarker = 'data-qdhelper-card-local-unlocked';
        var classMarker = 'data-qdhelper-card-lock-classes';
        var statusMarker = 'data-qdhelper-card-status-hidden';
        var styleMarker = 'data-qdhelper-card-lock-style';
        var noInlineStyle = '__qdh_no_inline_style__';

        var observer = null;
        var scheduled = false;
        var active = false;
        var clickInstalled = false;
        var modelRecords = [];

        function hasOwn(target, key) {
          return Object.prototype.hasOwnProperty.call(target, key);
        }

        function textOf(node) {
          return String(node && node.textContent || '').replace(/\s+/g, ' ').trim();
        }

        function dataOf(vm) {
          if (!vm) return null;
          if (vm.data && typeof vm.data === 'object') return vm.data;
          var props = vm['${'$'}props'];
          if (props && props.data && typeof props.data === 'object') return props.data;
          return null;
        }

        function cardVmOf(item) {
          var current = item;
          while (current) {
            var vm = current.__vue__;
            if (dataOf(vm)) return vm;
            var children = vm && vm['${'$'}children'];
            if (children) {
              for (var i = 0; i < children.length; i++) {
                if (children[i] && children[i]['${'$'}el'] === item && dataOf(children[i])) {
                  return children[i];
                }
              }
            }
            current = current.parentElement;
          }
          return null;
        }

        function recordOf(data) {
          if (!data) return null;
          for (var i = 0; i < modelRecords.length; i++) {
            if (modelRecords[i].data === data) return modelRecords[i];
          }
          return null;
        }

        function ownershipKeyOf(data) {
          if (!data) return null;
          if (hasOwn(data, 'HasNum')) return 'HasNum';
          if (hasOwn(data, 'hasNum')) return 'hasNum';
          return null;
        }

        function isUnowned(data) {
          var key = ownershipKeyOf(data);
          return key !== null && Number(data[key]) <= 0;
        }

        function forceUpdate(vm) {
          var update = vm && vm['${'$'}forceUpdate'];
          if (typeof update === 'function') {
            try { update.call(vm); } catch (ignored) {}
          }
        }

        function makeLocallyOwned(vm, data) {
          var record = recordOf(data);
          var key = ownershipKeyOf(data);
          if (!record && key !== null && Number(data[key]) <= 0) {
            record = { data: data, key: key, value: data[key], vm: vm };
            modelRecords.push(record);
          }
          if (!record) return false;
          var ownedValue = typeof record.value === 'string' ? '1' : 1;
          if (record.data[record.key] !== ownedValue) {
            record.data[record.key] = ownedValue;
            forceUpdate(vm || record.vm);
          }
          return true;
        }

        function nodesOf(item) {
          return [item].concat(Array.prototype.slice.call(item.querySelectorAll('*')));
        }

        function lockVisualScore(item) {
          var nodes = nodesOf(item);
          var count = 0;
          for (var i = 0; i < nodes.length; i++) {
            var style = window.getComputedStyle(nodes[i]);
            var filter = String(style.filter || style.webkitFilter || '').toLowerCase();
            if ((filter.indexOf('grayscale') >= 0 && filter.indexOf('grayscale(0') < 0) ||
                filter.indexOf('saturate(0') >= 0) {
              count++;
            }
          }
          return count;
        }

        function findLockClasses(item) {
          var names = Array.prototype.slice.call(item.classList || []);
          var semantic = names.filter(function(name) {
            return /not.?get|unowned|unobtain|locked/i.test(name);
          });
          if (semantic.length) return semantic;

          var beforeScore = lockVisualScore(item);
          if (!beforeScore) return [];
          var beforeRect = item.getBoundingClientRect();
          for (var i = 0; i < names.length; i++) {
            var name = names[i];
            item.classList.remove(name);
            var afterScore = lockVisualScore(item);
            var afterRect = item.getBoundingClientRect();
            item.classList.add(name);
            var geometryStable = Math.abs(beforeRect.width - afterRect.width) < 1 &&
              Math.abs(beforeRect.height - afterRect.height) < 1;
            if (afterScore < beforeScore && geometryStable) {
              return [name];
            }
          }
          return [];
        }

        function savedLockClasses(item) {
          var saved = item.getAttribute(classMarker);
          if (saved === null) return null;
          try { return JSON.parse(saved); } catch (error) { return []; }
        }

        function rememberInlineStyle(node, marker) {
          if (node.hasAttribute(marker)) return;
          var value = node.getAttribute('style');
          node.setAttribute(marker, value === null ? noInlineStyle : value);
        }

        function hideOwnershipStatus(item) {
          var nodes = item.querySelectorAll('p, span, div');
          for (var i = 0; i < nodes.length; i++) {
            var text = textOf(nodes[i]);
            if (!/^(暂未获得|尚未获得|未获得|未拥有|已拥有\s*\d+\s*张)$/.test(text)) continue;
            rememberInlineStyle(nodes[i], statusMarker);
            nodes[i].style.setProperty('display', 'none', 'important');
          }
        }

        function normalizeLockStyles(item) {
          var nodes = nodesOf(item);
          for (var i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            var style = window.getComputedStyle(node);
            var filter = String(style.filter || style.webkitFilter || '').toLowerCase();
            var clearFilter = (filter.indexOf('grayscale') >= 0 && filter.indexOf('grayscale(0') < 0) ||
              filter.indexOf('saturate(0') >= 0;
            var clearOpacity = node.tagName === 'IMG' && Number(style.opacity) < 0.99;
            var clearPointer = node === item && style.pointerEvents === 'none';
            if (!clearFilter && !clearOpacity && !clearPointer) continue;
            rememberInlineStyle(node, styleMarker);
            if (clearFilter) {
              node.style.setProperty('filter', 'none', 'important');
              node.style.setProperty('-webkit-filter', 'none', 'important');
            }
            if (clearOpacity) node.style.setProperty('opacity', '1', 'important');
            if (clearPointer) node.style.setProperty('pointer-events', 'auto', 'important');
          }
        }

        function containsLockedStatus(item) {
          return /暂未获得|尚未获得|未获得|未拥有/.test(textOf(item));
        }

        function unlock(item) {
          if (!item || item.tagName !== 'LI') return false;
          var vm = cardVmOf(item);
          var data = dataOf(vm);
          if (!item.querySelector('h5') && !(data && data.CardName)) return false;

          var names = savedLockClasses(item);
          if (names === null) names = findLockClasses(item);
          var record = recordOf(data);
          var locked = item.hasAttribute(itemMarker) || record !== null || isUnowned(data) ||
            names.length > 0 || containsLockedStatus(item);
          if (!locked) return false;

          item.setAttribute(itemMarker, '1');
          if (!item.hasAttribute(classMarker)) {
            item.setAttribute(classMarker, JSON.stringify(names));
          }
          for (var i = 0; i < names.length; i++) {
            item.classList.remove(names[i]);
          }
          makeLocallyOwned(vm, data);
          hideOwnershipStatus(item);
          normalizeLockStyles(item);
          return true;
        }

        function scan() {
          var root = document.getElementById('j-cardListWrap');
          if (!root) return 0;
          var changed = 0;
          var items = root.children;
          for (var i = 0; i < items.length; i++) {
            if (unlock(items[i])) changed++;
          }
          return changed;
        }

        function restore() {
          for (var r = 0; r < modelRecords.length; r++) {
            var record = modelRecords[r];
            record.data[record.key] = record.value;
            forceUpdate(record.vm);
          }
          modelRecords = [];

          var styled = document.querySelectorAll('[' + styleMarker + ']');
          for (var s = 0; s < styled.length; s++) {
            restoreInlineStyle(styled[s], styleMarker);
          }
          var statuses = document.querySelectorAll('[' + statusMarker + ']');
          for (var h = 0; h < statuses.length; h++) {
            restoreInlineStyle(statuses[h], statusMarker);
          }

          var items = document.querySelectorAll('li[' + itemMarker + ']');
          for (var i = 0; i < items.length; i++) {
            var names;
            try { names = JSON.parse(items[i].getAttribute(classMarker) || '[]'); } catch (error) { names = []; }
            for (var j = 0; j < names.length; j++) {
              items[i].classList.add(names[j]);
            }
            items[i].removeAttribute(classMarker);
            items[i].removeAttribute(itemMarker);
          }
          return items.length;
        }

        function restoreInlineStyle(node, marker) {
          var saved = node.getAttribute(marker);
          if (saved === noInlineStyle) {
            node.removeAttribute('style');
          } else if (saved !== null) {
            node.setAttribute('style', saved);
          }
          node.removeAttribute(marker);
        }

        function fallbackPreview(data, vm) {
          if (!data || !data.CardId) return false;
          var userId = vm && vm.userId || data.UserId || data.userId || '';
          var route = '';
          if (Number(data.Quality) > 0) {
            route = '/h5/roleCard/roleCardPreview?cardId=' + encodeURIComponent(data.CardId) +
              '&roleId=' + encodeURIComponent(data.RoleId || '') +
              '&userId=' + encodeURIComponent(userId);
          } else if (Number(data.Level) > 0) {
            route = '/h5/roleCard/specialCardPreview?cardId=' + encodeURIComponent(data.CardId) +
              '&userId=' + encodeURIComponent(userId);
          }
          if (!route) return false;
          window.location.href = route;
          return true;
        }

        function handleUnlockedClick(event) {
          if (!active) return;
          var item = event.target;
          while (item && item !== document.documentElement) {
            if (item.tagName === 'LI' && item.hasAttribute(itemMarker)) break;
            item = item.parentElement;
          }
          if (!item || item === document.documentElement) return;

          var vm = cardVmOf(item);
          var data = dataOf(vm);
          var hostPreview = vm && typeof vm.handleCardClick === 'function';
          var canFallback = data && data.CardId && (Number(data.Quality) > 0 || Number(data.Level) > 0);
          if (!hostPreview && !canFallback) return;

          event.preventDefault();
          event.stopPropagation();
          if (typeof event.stopImmediatePropagation === 'function') event.stopImmediatePropagation();
          if (hostPreview) {
            try {
              vm.handleCardClick();
              return;
            } catch (ignored) {}
          }
          fallbackPreview(data, vm);
        }

        function installClickHandler() {
          if (clickInstalled) return;
          document.addEventListener('click', handleUnlockedClick, true);
          clickInstalled = true;
        }

        function removeClickHandler() {
          if (!clickInstalled) return;
          document.removeEventListener('click', handleUnlockedClick, true);
          clickInstalled = false;
        }

        function scheduleScan() {
          if (!active || scheduled) return;
          scheduled = true;
          window.requestAnimationFrame(function() {
            scheduled = false;
            if (active) scan();
          });
        }

        function observe() {
          if (observer) return;
          var target = document.body || document.documentElement;
          if (!target) return;
          observer = new MutationObserver(scheduleScan);
          observer.observe(target, {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: ['class'],
            characterData: true
          });
        }

        function setEnabled(value) {
          active = Boolean(value);
          if (active) {
            var changed = scan();
            installClickHandler();
            observe();
            return 'enabled:' + changed;
          }
          if (observer) {
            observer.disconnect();
            observer = null;
          }
          removeClickHandler();
          return 'disabled:' + restore();
        }

        window[stateKey] = { setEnabled: setEnabled };
        return setEnabled(enabled);
      } catch (error) {
        return 'error:' + error;
      }
    })();
"""
