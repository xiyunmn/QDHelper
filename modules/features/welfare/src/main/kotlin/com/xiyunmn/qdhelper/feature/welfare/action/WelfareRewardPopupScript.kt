package com.xiyunmn.qdhelper.feature.welfare.action

internal fun welfareRewardPopupScript(enabled: Boolean): String = """
    (function(enabled) {
      var key = '__qdhelperRewardPopup';
      var previous = window[key];
      if (!enabled) {
        if (previous) previous.dispose();
        return 'disabled';
      }
      if (location.hostname.toLowerCase() !== 'h5.if.qidian.com' ||
          location.pathname.replace(/\/${'$'}/, '').toLowerCase() !== '/new/welfarecenter') return 'outside-page';
      if (previous) { previous.sweep(); return 'active:closed=' + previous.closed; }
      if (!document.documentElement) return 'not-ready';
      // Dispose the legacy text scanner if this document survived a module hot replacement.
      if (window.__qdhRewardPopupObserver) {
        window.__qdhRewardPopupObserver.disconnect(); delete window.__qdhRewardPopupObserver;
      }
      var state = { closed: 0, disposed: false }, closing = new WeakSet();
      var style = document.createElement('style');
      style.textContent = '.prize-dialog[data-qdh-reward-hidden]{visibility:hidden!important;pointer-events:none!important}';
      (document.head || document.documentElement).appendChild(style);
      function sweep() {
        if (state.disposed) return;
        if (location.hostname.toLowerCase() !== 'h5.if.qidian.com' ||
            location.pathname.replace(/\/${'$'}/, '').toLowerCase() !== '/new/welfarecenter') { state.dispose(); return; }
        document.querySelectorAll('.prize-dialog').forEach(function(popup) {
          var content = popup.querySelector('[data-popup-visible]');
          if (!content || content.getAttribute('data-popup-visible') !== 'true') {
            closing.delete(popup); popup.removeAttribute('data-qdh-reward-hidden'); return;
          }
          // BasePopup is also used for automatic reading rewards. Require its real reward
          // artwork and close control (or its explicit timer), never arbitrary page text.
          if (!popup.querySelector('.reward-active-gift,.reward-inactive-gift,.multi-reward-list')) return;
          var close = popup.querySelector('.reward-anchor-btn');
          if (!close && content.getAttribute('data-popup-autoclose') !== 'true') return;
          popup.setAttribute('data-qdh-reward-hidden', '');
          if (closing.has(popup)) return;
          closing.add(popup);
          if (close) {
            // Use the host handler: it clears the ad lock, Vue state and no-scroll class,
            // and lets the page run its normal reward-close refresh path.
            try { close.click(); state.closed++; }
            catch (error) { closing.delete(popup); popup.removeAttribute('data-qdh-reward-hidden'); }
          }
        });
      }
      var observer = new MutationObserver(sweep);
      state.sweep = sweep;
      state.dispose = function() {
        if (state.disposed) return;
        state.disposed = true; observer.disconnect();
        document.querySelectorAll('[data-qdh-reward-hidden]').forEach(function(node) { node.removeAttribute('data-qdh-reward-hidden'); });
        style.remove(); window.removeEventListener('pagehide', state.dispose);
        if (window[key] === state) delete window[key];
      };
      observer.observe(document.documentElement, {
        subtree: true, childList: true, attributes: true,
        attributeFilter: ['data-popup-visible', 'data-popup-autoclose', 'class']
      });
      window.addEventListener('pagehide', state.dispose);
      window[key] = state;
      sweep();
      return 'installed:closed=' + state.closed;
    })($enabled);
""".trimIndent()
