package com.xiyunmn.qdhelper.feature.welfare.action

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

class WelfareRewardPopupScriptTest {
    private val enable = JSONObject.quote(welfareRewardPopupScript(true))
    private val disable = JSONObject.quote(welfareRewardPopupScript(false))

    @Test fun closesOnlyRewardArtworkUsingTheHostHandlerAndDoesNotRepeatClicks() = runScript("""
        const unrelated = popup({ artwork: false });
        const reward = popup({}); // A multi-reward title may be an image with no text content.
        popups.push(unrelated, reward);
        eval($enable);
        assert.equal(unrelated.clicks, 0); assert.equal(reward.clicks, 1);
        assert(reward.hidden); assert.equal(hostUnlocks, 1);
        observer.callback(); eval($enable);
        assert.equal(reward.clicks, 1); assert.equal(observers, 1);
        reward.visible = 'false'; observer.callback();
        assert(!reward.hidden);
        reward.visible = 'true'; observer.callback();
        assert.equal(reward.clicks, 2);
    """)

    @Test fun handlesLateDomAndVisibilityChangesAndRestoresItsOwnStylesWhenDisabled() = runScript("""
        eval($enable);
        assert.equal(observedRoot, document.documentElement);
        assert(options.attributes && options.childList && options.subtree);
        const reward = popup({ visible: 'false' }); popups.push(reward); observer.callback();
        assert.equal(reward.clicks, 0);
        reward.visible = 'true'; observer.callback(); assert.equal(reward.clicks, 1);
        const timed = popup({ timed: true }); popups.push(timed); observer.callback();
        assert(timed.hidden); assert.equal(timed.clicks, 0);
        eval($disable);
        assert(disconnected && removedStyle);
        assert(!reward.hidden && !timed.hidden);
        assert.equal(window.__qdhelperRewardPopup, undefined);
        assert.equal(listeners.size, 0);
        observer.callback(); assert.equal(reward.clicks, 1);
    """)

    @Test fun staysOffOtherPagesAndCleansUpOnNavigation() = runScript("""
        location.pathname = '/other'; eval($enable); assert.equal(observers, 0);
        location.pathname = '/new/welfareCenter'; eval($enable); assert.equal(observers, 1);
        location.pathname = '/other'; observer.callback();
        assert(disconnected && removedStyle); assert.equal(window.__qdhelperRewardPopup, undefined);
        location.pathname = '/new/welfareCenter'; eval($enable);
        listeners.get('pagehide')(); assert.equal(window.__qdhelperRewardPopup, undefined);
    """)

    @Test fun failedCloseLeavesThePopupUsable() = runScript("""
        const reward = popup({ fail: true }); popups.push(reward);
        eval($enable); assert(!reward.hidden); assert.equal(hostUnlocks, 0);
    """)

    private fun runScript(assertions: String) {
        val fixture = """
            const assert = require('assert'); global.window = global;
            global.location = { hostname: 'h5.if.qidian.com', pathname: '/new/welfareCenter' };
            const popups = [], listeners = new Map();
            let observer, observers = 0, disconnected = false, removedStyle = false, hostUnlocks = 0, options, observedRoot;
            global.addEventListener = (name, fn) => listeners.set(name, fn);
            global.removeEventListener = (name, fn) => { assert.equal(listeners.get(name), fn); listeners.delete(name); };
            global.MutationObserver = class {
              constructor(callback) { this.callback = callback; observer = this; observers++; }
              observe(root, opts) { observedRoot = root; options = opts; }
              disconnect() { disconnected = true; }
            };
            global.document = {
              documentElement: {}, head: { appendChild: node => {} },
              createElement: () => ({ remove() { removedStyle = true; } }),
              querySelectorAll: selector => selector === '.prize-dialog' ? popups : popups.filter(p => p.hidden)
            };
            function popup(config) {
              return {
                visible: config.visible || 'true', hidden: false, clicks: 0,
                setAttribute(name) { assert.equal(name, 'data-qdh-reward-hidden'); this.hidden = true; },
                removeAttribute(name) { assert.equal(name, 'data-qdh-reward-hidden'); this.hidden = false; },
                querySelector(selector) {
                  if (selector === '[data-popup-visible]') return { getAttribute: name => name === 'data-popup-visible' ? this.visible : String(!!config.timed) };
                  if (selector === '.reward-anchor-btn') return config.timed ? null : { click: () => {
                    this.clicks++; if (config.fail) throw Error('host'); hostUnlocks++;
                  } };
                  return config.artwork === false ? null : {};
                }
              };
            }
        """.trimIndent()
        val file = Files.createTempFile("welfare-popup-contract-", ".js")
        try {
            Files.writeString(file, fixture + "\n" + assertions.trimIndent())
            val process = runCatching { ProcessBuilder("node", file.toString()).redirectErrorStream(true).start() }.getOrElse {
                Assume.assumeNoException("Node.js is needed for WebView script contract tests", it); return
            }
            val completed = process.waitFor(10, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly()
            assertTrue("Popup script timed out", completed)
            assertEquals(process.inputStream.bufferedReader().readText(), 0, process.exitValue())
        } finally { Files.deleteIfExists(file) }
    }
}
