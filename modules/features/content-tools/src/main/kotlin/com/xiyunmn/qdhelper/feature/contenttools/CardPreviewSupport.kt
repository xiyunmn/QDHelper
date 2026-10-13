package com.xiyunmn.qdhelper.feature.contenttools

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.ViewLongPressCallbacks
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.hook.concurrency.ManagedExecutor
import com.xiyunmn.qdhelper.hook.view.LongPressRegistration
import com.xiyunmn.qdhelper.hook.view.LongPressRegistrationResult
import com.xiyunmn.qdhelper.hook.view.LongPressResult
import com.xiyunmn.qdhelper.shared.io.HttpTransport
import com.xiyunmn.qdhelper.shared.media.ImageGalleryStore
import com.xiyunmn.qdhelper.shared.media.RemoteImageDownloader
import java.net.URI
import java.util.Collections
import java.util.WeakHashMap
import org.json.JSONObject
import org.json.JSONTokener

internal object CardPreviewPolicy {
    private const val HOST = "h5.if.qidian.com"
    private const val ROLE_PREVIEW_PATH = "/h5/roleCard/roleCardPreview"
    private const val SPECIAL_PREVIEW_PATH = "/h5/roleCard/specialCardPreview"
    private const val SCRIPT_ENABLED_TOKEN = "__QDHELPER_ENABLED__"

    fun isPreviewUrl(rawUrl: String?): Boolean {
        val uri = rawUrl?.trim()?.takeIf(String::isNotEmpty)?.let { value ->
            runCatching { URI(value) }.getOrNull()
        } ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (!uri.host.equals(HOST, ignoreCase = true)) return false
        return uri.path.orEmpty().trimEnd('/') in setOf(ROLE_PREVIEW_PATH, SPECIAL_PREVIEW_PATH)
    }

    fun unlockScript(enabled: Boolean): String = CARD_PREVIEW_UNLOCK_SCRIPT_TEMPLATE.replace(
        SCRIPT_ENABLED_TOKEN,
        enabled.toString(),
    )

    fun saveBridgeScript(enabled: Boolean): String = CARD_PREVIEW_SAVE_BRIDGE_SCRIPT_TEMPLATE.replace(
        SCRIPT_ENABLED_TOKEN,
        enabled.toString(),
    )

    fun saveTargetScript(): String = CARD_PREVIEW_SAVE_TARGET_SCRIPT

    fun parseSaveTarget(rawResult: String?): CardPreviewSaveTarget? {
        val first = rawResult?.takeIf(String::isNotBlank)?.let { raw ->
            runCatching { JSONTokener(raw).nextValue() }.getOrNull()
        } ?: return null
        val json = when (first) {
            is JSONObject -> first
            is String -> runCatching { JSONObject(first) }.getOrNull()
            else -> null
        } ?: return null
        val rawUrl = json.optString("url").trim().takeIf { it.isNotEmpty() && it.length <= 8_192 }
            ?: return null
        val url = HttpTransport.normalizeHttpUrl(rawUrl) ?: return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        return CardPreviewSaveTarget(
            url = url,
            cardId = json.optString("cardId").trim().take(64),
            cardName = json.optString("cardName").trim().take(80),
        )
    }
}

internal data class CardPreviewSaveTarget(
    val url: String,
    val cardId: String,
    val cardName: String,
) {
    val fileStem: String
        get() = "card_preview_${cardId.ifBlank { "unknown" }}_${cardName.ifBlank { "card" }}"
}

internal object CardPreviewImageSaver {
    private val worker = ManagedExecutor(
        ownerKey = "feature.content-tools.card-preview-save",
        threadName = "QDHelper-card-preview-save",
        onFailure = { throwable -> FeatureHooks.e("Card preview image save worker failed", throwable) },
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val registrations = Collections.synchronizedMap(WeakHashMap<View, AutoCloseable>())
    private val savingViews = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<View, Boolean>()),
    )

    fun bind(webView: Any, enabled: Boolean) {
        val view = webView as? View ?: return
        if (!enabled) {
            unbind(view)
            return
        }
        synchronized(registrations) {
            if (registrations.containsKey(view)) return
        }
        val result = ViewLongPressCallbacks.register(
            target = view,
            registration = LongPressRegistration(
                owner = OwnerId("content-tools"),
                id = "card-preview-save",
                priority = 120,
            ),
        ) { target, _ ->
            val targetView = target as? View ?: return@register LongPressResult.Pass
            if (!SettingsRuntime.isCardPreviewLongPressSaveEnabled(targetView.context)) {
                unbind(targetView)
                return@register LongPressResult.Pass
            }
            if (!CardPreviewPolicy.isPreviewUrl(CardPlazaWebViewAccess.urlOf(target))) {
                return@register LongPressResult.Pass
            }
            requestSave(target, targetView)
            LongPressResult.Return(true)
        }
        if (result is LongPressRegistrationResult.Registered) {
            registrations[view] = result.registration
        }
    }

    fun clear() {
        val active = synchronized(registrations) {
            registrations.values.toList().also { registrations.clear() }
        }
        active.forEach { registration -> runCatching { registration.close() } }
        savingViews.clear()
    }

    private fun unbind(view: View) {
        registrations.remove(view)?.let { registration -> runCatching { registration.close() } }
        savingViews.remove(view)
    }

    private fun requestSave(webView: Any, view: View) {
        if (!savingViews.add(view)) {
            toast(view.context, "正在保存卡牌图片")
            return
        }
        val queued = CardPlazaWebViewAccess.evaluate(
            webView = webView,
            script = CardPreviewPolicy.saveTargetScript(),
            onResult = { rawResult ->
                val target = CardPreviewPolicy.parseSaveTarget(rawResult)
                if (target == null) {
                    savingViews.remove(view)
                    toast(view.context, "请长按卡牌图片")
                    return@evaluate
                }
                if (!SettingsRuntime.isCardPreviewLongPressSaveEnabled(view.context)) {
                    savingViews.remove(view)
                    return@evaluate
                }
                save(view, target)
            },
        )
        if (!queued) {
            savingViews.remove(view)
            toast(view.context, "未读取到卡牌图片")
            FeatureHooks.w("Card preview image target evaluation unavailable")
        }
    }

    private fun save(view: View, target: CardPreviewSaveTarget) {
        val context = view.context.applicationContext ?: view.context
        val accepted = worker.execute {
            val saved = runCatching {
                val data = RemoteImageDownloader.download(target.url)
                data != null && ImageGalleryStore.save(context, data, target.fileStem)
            }.onFailure { throwable ->
                FeatureHooks.e("Card preview image save failed unexpectedly", throwable)
            }.getOrDefault(false)
            val cardId = target.cardId.ifBlank { "unknown" }
            if (saved) {
                FeatureHooks.i("Card preview image saved to gallery: cardId=$cardId")
            } else {
                FeatureHooks.w("Card preview image save failed: cardId=$cardId")
            }
            mainHandler.post {
                savingViews.remove(view)
                toast(view.context, if (saved) "已保存到相册" else "保存失败")
            }
        }
        if (!accepted) savingViews.remove(view)
    }

    private fun toast(context: Context, message: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        } else {
            mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
        }
    }
}

private const val CARD_PREVIEW_UNLOCK_SCRIPT_TEMPLATE = """
    (function() {
      try {
        var path = String(window.location.pathname || '').replace(/\/+$/, '');
        if (window.location.hostname !== 'h5.if.qidian.com' ||
            (path !== '/h5/roleCard/roleCardPreview' && path !== '/h5/roleCard/specialCardPreview')) {
          return 'ignored';
        }

        var enabled = __QDHELPER_ENABLED__;
        var stateKey = '__qdhCardPreviewUnlockV1';
        var existing = window[stateKey];
        if (existing && typeof existing.setEnabled === 'function') {
          return existing.setEnabled(enabled);
        }

        var itemMarker = 'data-qdhelper-card-preview-unlocked';
        var classMarker = 'data-qdhelper-card-preview-lock-classes';
        var statusMarker = 'data-qdhelper-card-preview-status-hidden';
        var styleMarker = 'data-qdhelper-card-preview-lock-style';
        var noInlineStyle = '__qdh_no_inline_style__';
        var active = false;
        var observer = null;
        var scheduled = false;
        var records = [];

        function hasOwn(target, key) {
          return Object.prototype.hasOwnProperty.call(target, key);
        }

        function visitVm(vm, seen, depth) {
          if (!vm || depth > 12 || seen.indexOf(vm) >= 0) return null;
          seen.push(vm);
          if (hasOwn(vm, 'hasNum') && typeof vm.previewImage === 'function') return vm;
          var children = vm['${'$'}children'] || [];
          for (var i = 0; i < children.length; i++) {
            var found = visitVm(children[i], seen, depth + 1);
            if (found) return found;
          }
          return null;
        }

        function previewVm() {
          var seen = [];
          var app = document.getElementById('app');
          var found = visitVm(app && app.__vue__, seen, 0);
          if (found) return found;
          var nodes = document.querySelectorAll('*');
          for (var i = 0; i < nodes.length; i++) {
            found = visitVm(nodes[i].__vue__, seen, 0);
            if (found) return found;
          }
          return null;
        }

        function recordOf(vm) {
          for (var i = 0; i < records.length; i++) {
            if (records[i].vm === vm) return records[i];
          }
          return null;
        }

        function forceUpdate(vm) {
          var update = vm && vm['${'$'}forceUpdate'];
          if (typeof update === 'function') {
            try { update.call(vm); } catch (ignored) {}
          }
        }

        function unlockModel(vm) {
          if (!vm || !String(vm.cardName || '').trim()) return false;
          var record = recordOf(vm);
          if (!record && Number(vm.hasNum) <= 0) {
            record = { vm: vm, value: vm.hasNum };
            records.push(record);
          }
          if (!record) return false;
          if (Number(vm.hasNum) <= 0) {
            vm.hasNum = typeof record.value === 'string' ? '1' : 1;
            forceUpdate(vm);
          }
          return true;
        }

        function rememberInlineStyle(node, marker) {
          if (node.hasAttribute(marker)) return;
          var value = node.getAttribute('style');
          node.setAttribute(marker, value === null ? noInlineStyle : value);
        }

        function unlockDom() {
          var containers = document.querySelectorAll('[class*="notGet"], [class*="not-get"]');
          for (var i = 0; i < containers.length; i++) {
            var node = containers[i];
            var names = Array.prototype.slice.call(node.classList || []).filter(function(name) {
              return /not.?get/i.test(name);
            });
            if (!node.hasAttribute(classMarker)) node.setAttribute(classMarker, JSON.stringify(names));
            node.setAttribute(itemMarker, '1');
            for (var n = 0; n < names.length; n++) node.classList.remove(names[n]);
          }

          var statuses = document.querySelectorAll('[title="暂未获得"], img[alt="暂未获得"]');
          for (var s = 0; s < statuses.length; s++) {
            var status = statuses[s].closest('[title="暂未获得"]') || statuses[s];
            rememberInlineStyle(status, statusMarker);
            status.style.setProperty('display', 'none', 'important');
          }

          var images = document.querySelectorAll('img');
          for (var j = 0; j < images.length; j++) {
            var style = window.getComputedStyle(images[j]);
            var filter = String(style.filter || style.webkitFilter || '').toLowerCase();
            var desaturated = style.mixBlendMode === 'luminosity' ||
              (filter.indexOf('grayscale') >= 0 && filter.indexOf('grayscale(0') < 0) ||
              filter.indexOf('saturate(0') >= 0;
            if (!desaturated) continue;
            rememberInlineStyle(images[j], styleMarker);
            images[j].style.setProperty('mix-blend-mode', 'normal', 'important');
            images[j].style.setProperty('filter', 'none', 'important');
            images[j].style.setProperty('-webkit-filter', 'none', 'important');
            images[j].style.setProperty('opacity', '1', 'important');
          }
        }

        function scan() {
          var vm = previewVm();
          if (!unlockModel(vm)) return 0;
          unlockDom();
          return 1;
        }

        function restoreInlineStyle(node, marker) {
          var saved = node.getAttribute(marker);
          if (saved === noInlineStyle) node.removeAttribute('style');
          else if (saved !== null) node.setAttribute('style', saved);
          node.removeAttribute(marker);
        }

        function restore() {
          for (var r = 0; r < records.length; r++) {
            records[r].vm.hasNum = records[r].value;
            forceUpdate(records[r].vm);
          }
          records = [];

          var styled = document.querySelectorAll('[' + styleMarker + ']');
          for (var s = 0; s < styled.length; s++) restoreInlineStyle(styled[s], styleMarker);
          var statuses = document.querySelectorAll('[' + statusMarker + ']');
          for (var h = 0; h < statuses.length; h++) restoreInlineStyle(statuses[h], statusMarker);
          var containers = document.querySelectorAll('[' + itemMarker + ']');
          for (var i = 0; i < containers.length; i++) {
            var names;
            try { names = JSON.parse(containers[i].getAttribute(classMarker) || '[]'); }
            catch (error) { names = []; }
            for (var n = 0; n < names.length; n++) containers[i].classList.add(names[n]);
            containers[i].removeAttribute(classMarker);
            containers[i].removeAttribute(itemMarker);
          }
          return containers.length;
        }

        function scheduleScan() {
          if (!active || scheduled) return;
          scheduled = true;
          window.requestAnimationFrame(function() {
            scheduled = false;
            if (active) scan();
          });
        }

        function setEnabled(value) {
          active = Boolean(value);
          if (active) {
            if (!observer) {
              observer = new MutationObserver(scheduleScan);
              observer.observe(document.body || document.documentElement, {
                childList: true,
                subtree: true,
                attributes: true,
                attributeFilter: ['class']
              });
            }
            return 'enabled:' + scan();
          }
          if (observer) {
            observer.disconnect();
            observer = null;
          }
          return 'disabled:' + restore();
        }

        window[stateKey] = { setEnabled: setEnabled };
        return setEnabled(enabled);
      } catch (error) {
        return 'error:' + error;
      }
    })();
"""

private const val CARD_PREVIEW_SAVE_BRIDGE_SCRIPT_TEMPLATE = """
    (function() {
      try {
        var path = String(window.location.pathname || '').replace(/\/+$/, '');
        if (window.location.hostname !== 'h5.if.qidian.com' ||
            (path !== '/h5/roleCard/roleCardPreview' && path !== '/h5/roleCard/specialCardPreview')) {
          return 'ignored';
        }

        var enabled = __QDHELPER_ENABLED__;
        var stateKey = '__qdhCardPreviewSaveV1';
        var existing = window[stateKey];
        if (existing && typeof existing.setEnabled === 'function') {
          return existing.setEnabled(enabled);
        }

        var active = false;
        var lastCardTouchAt = 0;

        function isCardArea(target) {
          var node = target;
          while (node && node !== document.documentElement) {
            var title = node.getAttribute && node.getAttribute('title');
            var className = String(node.className || '');
            if (title === '卡片' || title === '卡框' || title === '人设' ||
                /cardDetailShow|cardBorder|imgBox/i.test(className)) {
              return true;
            }
            node = node.parentElement;
          }
          return false;
        }

        function rememberTouch(event) {
          lastCardTouchAt = active && isCardArea(event.target) ? Date.now() : 0;
        }

        function visitVm(vm, seen, depth) {
          if (!vm || depth > 12 || seen.indexOf(vm) >= 0) return null;
          seen.push(vm);
          if (hasOwn(vm, 'hasNum') && typeof vm.previewImage === 'function') return vm;
          var children = vm['${'$'}children'] || [];
          for (var i = 0; i < children.length; i++) {
            var found = visitVm(children[i], seen, depth + 1);
            if (found) return found;
          }
          return null;
        }

        function hasOwn(target, key) {
          return Object.prototype.hasOwnProperty.call(target, key);
        }

        function previewVm() {
          var seen = [];
          var app = document.getElementById('app');
          var found = visitVm(app && app.__vue__, seen, 0);
          if (found) return found;
          var nodes = document.querySelectorAll('*');
          for (var i = 0; i < nodes.length; i++) {
            found = visitVm(nodes[i].__vue__, seen, 0);
            if (found) return found;
          }
          return null;
        }

        function target() {
          if (!active || !lastCardTouchAt || Date.now() - lastCardTouchAt > 2500) return null;
          var vm = previewVm();
          if (!vm) return null;
          var currentWeal = Number(vm.wealType) === 3 && vm.previewWealItem && vm.previewWealItem.ResourceUrl;
          var multiple = vm.multipleImages || vm.MultipleImages || {};
          var url = String(currentWeal || vm.previewBigUrl || multiple.DynamicWebpUrl ||
            multiple.StaticImage || vm.bigImage || vm.cardImage || vm.image || '').trim();
          if (/^\/\//.test(url)) url = 'https:' + url;
          if (!/^https?:\/\//i.test(url)) return null;
          return JSON.stringify({
            url: url,
            cardId: String(vm.cardId || ''),
            cardName: String(vm.cardName || '')
          });
        }

        function setEnabled(value) {
          active = Boolean(value);
          lastCardTouchAt = 0;
          return active ? 'enabled' : 'disabled';
        }

        document.addEventListener('touchstart', rememberTouch, true);
        document.addEventListener('mousedown', rememberTouch, true);
        window[stateKey] = { setEnabled: setEnabled, target: target };
        return setEnabled(enabled);
      } catch (error) {
        return 'error:' + error;
      }
    })();
"""

private const val CARD_PREVIEW_SAVE_TARGET_SCRIPT = """
    (function() {
      var state = window.__qdhCardPreviewSaveV1;
      return state && typeof state.target === 'function' ? state.target() : null;
    })();
"""
