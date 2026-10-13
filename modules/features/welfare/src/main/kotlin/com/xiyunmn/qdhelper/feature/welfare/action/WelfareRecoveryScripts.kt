package com.xiyunmn.qdhelper.feature.welfare.action

import org.json.JSONObject

internal fun welfareArmReceiptScript(requestId: Long, callbackId: Int, url: String): String = """
    (function() {
      if (location.href !== ${JSONObject.quote(url)}) return false;
      var root = window.__qdhelperWelfareRecovery;
      if (!root) root = window.__qdhelperWelfareRecovery = {};
      root.active = { id: $requestId, callbackId: $callbackId, received: false, synced: false };
      if (typeof window.execCallback === 'function' && window.execCallback !== root.wrapper) {
        var original = window.execCallback;
        root.wrapper = function() {
          var active = root.active;
          var result = original.apply(this, arguments);
          if (active && root.active === active && Number(arguments[0]) === active.callbackId) active.received = true;
          return result;
        };
        window.execCallback = root.wrapper;
      }
      return true;
    })();
""".trimIndent()

/** The document marker prevents same-URL navigation from reusing an old callback. */
internal fun welfareReadReceiptScript(requestId: Long, url: String): String = """
    (function() {
      var root = window.__qdhelperWelfareRecovery;
      if (location.href !== ${JSONObject.quote(url)} || !root || !root.active ||
          root.active.id !== $requestId) return null;
      return { id: $requestId, received: !!root.active.received };
    })();
""".trimIndent()

/**
 * The welfare page listens on document for mainPage-refresh and reads its GET mainPage data.
 * Its silence option skips the loading overlay and updates the existing Vue component.
 * Dispatch is at most once per request, with no reward callback, navigation, or DOM state edits.
 */
internal fun welfareSyncPageScript(requestId: Long, url: String): String = """
    (function() {
      var root = window.__qdhelperWelfareRecovery, active = root && root.active;
      if (location.href !== ${JSONObject.quote(url)} || !active || active.id !== $requestId || active.synced ||
          typeof CustomEvent !== 'function' || !document.querySelector('.welfare_container')) return false;
      active.synced = true;
      document.dispatchEvent(new CustomEvent('mainPage-refresh', { detail: { silence: true } }));
      return true;
    })();
""".trimIndent()
