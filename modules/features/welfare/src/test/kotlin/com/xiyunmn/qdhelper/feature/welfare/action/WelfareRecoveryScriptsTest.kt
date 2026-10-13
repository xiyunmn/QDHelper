package com.xiyunmn.qdhelper.feature.welfare.action

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

class WelfareRecoveryScriptsTest {
    private val url = "https://h5.if.qidian.com/new/welfareCenter"

    @Test fun callbackReceiptRequiresTheActualHandlerAndCurrentDocument() {
        runJavaScript("""
            const assert = require('assert');
            global.window = global;
            global.location = { href: ${JSONObject.quote(url)} };
            const payload = { value: 42 }, result = {};
            global.execCallback = function(id, data) {
              assert.strictEqual(this, global); assert.strictEqual(id, 7);
              assert.strictEqual(data, payload); return result;
            };
            eval(${JSONObject.quote(welfareArmReceiptScript(1, 7, url))});
            assert.strictEqual(eval(${JSONObject.quote(welfareReadReceiptScript(1, url))}).received, false);
            assert.strictEqual(execCallback(7, payload), result);
            assert.strictEqual(eval(${JSONObject.quote(welfareReadReceiptScript(1, url))}).received, true);
            const failure = new Error('host callback');
            global.execCallback = function() { throw failure; };
            eval(${JSONObject.quote(welfareArmReceiptScript(2, 7, url))});
            assert.throws(() => execCallback(7, payload), e => e === failure);
            assert.strictEqual(eval(${JSONObject.quote(welfareReadReceiptScript(2, url))}).received, false);
            delete global.__qdhelperWelfareRecovery;
            assert.strictEqual(eval(${JSONObject.quote(welfareReadReceiptScript(2, url))}), null);
            global.location.href = 'https://h5.if.qidian.com/other';
            assert.strictEqual(eval(${JSONObject.quote(welfareArmReceiptScript(3, 7, url))}), false);
        """.trimIndent())
    }

    @Test fun receiptIgnoresOldCallbackAndDoesNotStackWrappers() {
        runJavaScript("""
            const assert = require('assert');
            global.window = global;
            global.location = { href: ${JSONObject.quote(url)} };
            let calls = 0;
            global.execCallback = () => ++calls;
            eval(${JSONObject.quote(welfareArmReceiptScript(1, 7, url))});
            const wrapper = execCallback;
            eval(${JSONObject.quote(welfareArmReceiptScript(2, 8, url))});
            assert.strictEqual(execCallback, wrapper);
            assert.strictEqual(execCallback(7, {}), 1);
            assert.strictEqual(eval(${JSONObject.quote(welfareReadReceiptScript(2, url))}).received, false);
            assert.strictEqual(eval(${JSONObject.quote(welfareReadReceiptScript(1, url))}), null);
            assert.strictEqual(execCallback(8, {}), 2);
            assert.strictEqual(eval(${JSONObject.quote(welfareReadReceiptScript(2, url))}).received, true);
        """.trimIndent())
    }

    @Test fun silentSyncDispatchesOnlyOneDataEventWithoutChangingPageOrRewards() {
        val script = JSONObject.quote(welfareSyncPageScript(5, url))
        runJavaScript(browserFixture() + """
            eval(${JSONObject.quote(welfareArmReceiptScript(5, 7, url))});
            assert.strictEqual(eval($script), true);
            assert.strictEqual(events.length, 1);
            assert.strictEqual(events[0].type, 'mainPage-refresh');
            assert.deepStrictEqual(events[0].detail, { silence: true });
            assert.strictEqual(eval($script), false);
            assert.strictEqual(events.length, 1);
            assert.strictEqual(hostCallbacks, 0);
            assert.strictEqual(document.querySelector('.welfare_container'), container);
            assert.strictEqual(container.buttonText, '待完成');
            assert.strictEqual(container.scrollTop, 900);
            assert.strictEqual(window.scrollY, 120);
            assert.strictEqual(location.href, ${JSONObject.quote(url)});
        """.trimIndent())
    }

    @Test fun newDocumentMissingSurfaceOrNavigationNeverSyncsAnOldRequest() {
        val script = JSONObject.quote(welfareSyncPageScript(5, url))
        runJavaScript(browserFixture() + """
            assert.strictEqual(eval($script), false);
            eval(${JSONObject.quote(welfareArmReceiptScript(5, 7, url))});
            document.querySelector = () => null;
            assert.strictEqual(eval($script), false);
            document.querySelector = () => container;
            location.href = 'https://h5.if.qidian.com/other';
            assert.strictEqual(eval($script), false);
            location.href = ${JSONObject.quote(url)};
            eval(${JSONObject.quote(welfareArmReceiptScript(6, 8, url))});
            assert.strictEqual(eval($script), false);
            delete global.__qdhelperWelfareRecovery;
            assert.strictEqual(eval($script), false);
            assert.strictEqual(events.length, 0);
            assert.strictEqual(hostCallbacks, 0);
        """.trimIndent())
    }

    private fun browserFixture(): String = """
        const assert = require('assert');
        global.window = global;
        const unexpected = () => { throw new Error('Page navigation or scroll mutation'); };
        global.location = { href: ${JSONObject.quote(url)}, reload: unexpected, replace: unexpected, assign: unexpected };
        global.scrollTo = unexpected; global.scrollX = 0; global.scrollY = 120;
        const container = Object.freeze({ buttonText: '待完成', scrollTop: 900 });
        const events = [];
        let hostCallbacks = 0;
        global.execCallback = () => { hostCallbacks++; };
        global.CustomEvent = class {
          constructor(type, options) { this.type = type; this.detail = options.detail; }
        };
        global.document = {
          querySelector: selector => selector === '.welfare_container' ? container : null,
          dispatchEvent: event => { events.push(event); return true; }
        };

    """.trimIndent() + "\n"

    private fun runJavaScript(script: String) {
        // A file avoids Windows command-line quote rewriting of JavaScript string literals.
        val file = Files.createTempFile("welfare-script-contract-", ".js")
        try {
            Files.writeString(file, script)
            val process = runCatching {
                ProcessBuilder("node", file.toString()).redirectErrorStream(true).start()
            }.getOrElse {
                Assume.assumeNoException("Node.js is needed for the executable WebView script contract tests", it)
                return
            }
            val completed = process.waitFor(10, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly()
            assertTrue("JavaScript contract test timed out", completed)
            assertEquals(process.inputStream.bufferedReader().readText(), 0, process.exitValue())
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
