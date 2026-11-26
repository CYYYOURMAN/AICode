package com.aicode.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.webkit.WebView
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 手机控制 → App 内嵌 WebView 输入框的桥。
 *
 * 为什么需要：WebView 里的 input / contenteditable 不响应无障碍的 ACTION_SET_TEXT 和 ACTION_PASTE，
 * ControlService.typeText() 对 App 自己的网页输入框（比如 App 里的 DSH 对话页）打不进字。
 * 这里用 evaluateJavascript 直接写值并补发 input/change 事件，让页面（React、ProseMirror 等）以为用户真的输入了。
 *
 * 只对 App 自己的 WebView 有效：别的 App 的网页输入框走不到这里。
 */
object WebInputBridge {
    @Volatile
    private var ref: WeakReference<WebView>? = null

    @Volatile
    private var appCtx: Context? = null

    /** MainActivity 创建 WebView 后调用；重建 WebView 时再调用一次即可覆盖。 */
    fun attach(wv: WebView) {
        ref = WeakReference(wv)
        appCtx = wv.context.applicationContext
    }

    /**
     * 往当前 WebView 里处于焦点的输入框填字。成功返回 true。
     * 最多阻塞 2 秒等 JS 回调；调用方必须是后台线程（ControlService 的循环在 Dispatchers.IO）。
     */
    fun enterText(text: String): Boolean {
        val wv = ref?.get() ?: return false
        val quoted = JSONObject.quote(text)
        val js = """
            (function(t){
              var el = document.activeElement;
              if (!el) return 'no-focus';
              var tag = (el.tagName || '').toLowerCase();
              try {
                if (tag === 'input' || tag === 'textarea') {
                  var proto = (tag === 'textarea') ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                  var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                  if (desc && desc.set) { desc.set.call(el, t); } else { el.value = t; }
                  el.dispatchEvent(new Event('input', {bubbles: true}));
                  el.dispatchEvent(new Event('change', {bubbles: true}));
                  return 'ok';
                }
                if (el.isContentEditable) {
                  el.focus();
                  var sel = window.getSelection();
                  var r = document.createRange();
                  r.selectNodeContents(el);
                  sel.removeAllRanges();
                  sel.addRange(r);
                  var ok = false;
                  try { ok = document.execCommand('insertText', false, t); } catch (e) {}
                  if (!ok) {
                    el.textContent = t;
                    try {
                      el.dispatchEvent(new InputEvent('input', {bubbles: true, data: t, inputType: 'insertText'}));
                    } catch (e2) {
                      el.dispatchEvent(new Event('input', {bubbles: true}));
                    }
                  }
                  return 'ok';
                }
                return 'not-editable:' + tag;
              } catch (e) { return 'err:' + e; }
            })($quoted)
        """.trimIndent()

        val latch = CountDownLatch(1)
        val result = arrayOfNulls<String>(1)
        Handler(Looper.getMainLooper()).post {
            try {
                wv.evaluateJavascript(js) { v ->
                    result[0] = v
                    latch.countDown()
                }
            } catch (_: Throwable) {
                latch.countDown()
            }
        }
        val ok = try {
            latch.await(2, TimeUnit.SECONDS)
            result[0]?.contains("ok") == true
        } catch (_: InterruptedException) {
            false
        }
        // 失败时把 JS 的返回原因弹出来：既方便排查，也让用户知道「网页输入框暂时打不进字」
        if (!ok) {
            val why = result[0] ?: "无返回"
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    Toast.makeText(appCtx, "网页输入框没填进去（$why）", Toast.LENGTH_SHORT).show()
                }
            }
        }
        return ok
    }
}
