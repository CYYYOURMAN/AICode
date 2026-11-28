package com.aicode.app

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 手机控制 → App 内嵌 WebView 的 DOM 桥。
 *
 * 为什么需要（2026-11-28 真机实测）：
 * - 长会话页的无障碍节点 bounds 大面积塌缩成零高（[x,116,x,116]），composer 甚至不在无障碍树里；
 * - uiautomator dump 在 App 内只有 6-7 个空节点，拿不到 WebView；
 * - 按坐标点会因分辨率/滚动位置/键盘动画频繁点错，只能反复截图对位。
 *
 * 做法：在 App 自己的 WebView 里执行 JS，按 CSS 选择器或可见文字找元素并 click()，
 * 不碰坐标。只在「App 在前台、网页页可见」时生效；别的 App / 设置页自动回退无障碍。
 *
 * 挂载入口复用 [WebInputBridge.attach]（MainActivity 创建 WebView 时已调用），
 * 所以不需要改 MainActivity。
 */
object WebDomBridge {
    @Volatile
    private var ref: WeakReference<WebView>? = null

    /** MainActivity 创建 WebView 后调用；由 [WebInputBridge.attach] 转发。 */
    fun attach(wv: WebView) {
        ref = WeakReference(wv)
    }

    /** 是否有 WebView 引用（不代表当前可见，可见性在执行时判断）。 */
    fun hasWebView(): Boolean = ref?.get() != null

    /** 当前 WebView 是否可见（主线程读 isShown；调用方来自 IO 线程，所以投递一下）。 */
    fun visible(): Boolean {
        val wv = ref?.get() ?: return false
        var shown = false
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            shown = try {
                wv.isShown && wv.width > 0 && wv.height > 0
            } catch (_: Throwable) {
                false
            }
            latch.countDown()
        }
        return try {
            latch.await(500, TimeUnit.MILLISECONDS) && shown
        } catch (_: InterruptedException) {
            false
        }
    }

    /**
     * 按 CSS 选择器点击。成功返回 {ok:true,label/sel/scrolled}；
     * DOM 明确失败返回 {ok:false,reason:...}（selector-not-found / not-visible / disabled /
     * bad-selector / click-error）。只有 DOM 桥本身不可用（没有 WebView / 执行超时）才返回 null——
     * 调用方据此区分「元素点不到」和「DOM 根本没跑」，前者必须报错而不是回退无障碍。
     * 元素不在视口里时先 scrollIntoView 再点（DSH 的 WebView 物理滑动不生效，见 phone 命令实测）。
     */
    fun clickSelector(selector: String): JSONObject? {
        if (selector.isBlank()) return failure("bad-selector")
        return parse(eval(clickSelectorJs(selector)))
    }

    /**
     * 按文字点击：精确优先、子串次之，视口内优先、最内层次之；视口外先滚过去再点。
     * 失败同样返回结构化 reason（not-found / not-visible / disabled / click-error）。
     */
    fun clickText(text: String): JSONObject? {
        if (text.isBlank()) return failure("bad-selector")
        return parse(eval(clickTextJs(text)))
    }

    private fun failure(reason: String): JSONObject =
        JSONObject().put("ok", false).put("reason", reason)

    /** 列出当前可见的可交互元素（供 phone snapshot 展示）。 */
    fun snapshot(): JSONArray? {
        val r = parse(eval(SNAPSHOT_JS)) ?: return null
        if (!r.optBoolean("ok")) return null
        return r.optJSONArray("items")
    }

    // ---------------- 内部 ----------------

    /** 在 WebView 主线程执行 JS，最多等 [timeoutMs]；后台/不可见时立即返回 null。 */
    private fun eval(script: String, timeoutMs: Long = 2000): String? {
        val wv = ref?.get() ?: return null
        val latch = CountDownLatch(1)
        val out = arrayOfNulls<String>(1)
        Handler(Looper.getMainLooper()).post {
            try {
                // 网页页不在前台（设置页/别的 App）时不碰后台 DOM，免得误点
                if (!wv.isShown || wv.width <= 0 || wv.height <= 0) {
                    latch.countDown()
                    return@post
                }
                wv.evaluateJavascript(script) { v ->
                    out[0] = v
                    latch.countDown()
                }
            } catch (_: Throwable) {
                latch.countDown()
            }
        }
        val done = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        return if (done) out[0] else null
    }

    /** evaluateJavascript 的回调是「JS 返回值的 JSON 序列化」；字符串结果会再包一层引号。 */
    private fun parse(raw: String?): JSONObject? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val body = if (raw.startsWith("\"")) {
            try {
                JSONTokener(raw).nextValue() as? String ?: return null
            } catch (_: Throwable) {
                return null
            }
        } else {
            raw
        }
        return try {
            JSONObject(body)
        } catch (_: Throwable) {
            null
        }
    }

    private fun clickSelectorJs(selector: String): String {
        val q = JSONObject.quote(selector)
        return """
(function(s){
""" + HELPERS + """
  var el=null;
  try{ el=document.querySelector(s); }catch(e){ return JSON.stringify({ok:false,reason:'bad-selector'}); }
  if(!el) return JSON.stringify({ok:false,reason:'selector-not-found'});
  if(!__vc_shown(el)) return JSON.stringify({ok:false,reason:'not-visible'});
  if(el.disabled) return JSON.stringify({ok:false,reason:'disabled'});
  var scrolled=false;
  if(!__vc_inview(el)) scrolled=__vc_scroll(el);
  var desc={tag:el.tagName.toLowerCase(),label:__vc_label(el).slice(0,80),sel:__vc_sel(el)};
  try{ el.focus({preventScroll:true}); }catch(e){}
  try{ el.click(); }catch(e){ return JSON.stringify({ok:false,reason:'click-error',scrolled:scrolled}); }
  return JSON.stringify({ok:true,tag:desc.tag,label:desc.label,sel:desc.sel,scrolled:scrolled});
})(""" + q + """)
"""
    }

    private fun clickTextJs(text: String): String {
        val q = JSONObject.quote(text)
        return """
(function(t){
""" + HELPERS + """
  var all=document.querySelectorAll(__vc_act_query);
  var exact=[],part=[],hidden=[];
  for(var i=0;i<all.length;i++){
    var el=all[i];
    var L=__vc_label(el);
    var A=(el.getAttribute('aria-label')||'').trim();
    var P=(el.getAttribute('placeholder')||'').trim();
    // 「精确」可以来自文字，也可以来自语义标签；
    // 「包含」只认作者写的 aria-label/placeholder，绝不用 innerText 做包含匹配：
    // 消息行/整块容器的 innerText 很长，含目标词就会把「消息已送达」点成整条历史消息
    //（2026-11-28 独立验证踩过；本窗口复测「phone tap 消息已送达」也点开了消息）。
    var kind=(L===t||A===t)?'exact':(((A&&A.indexOf(t)>=0)||(P&&P.indexOf(t)>=0))?'part':'');
    if(!kind) continue;
    // 不见了的不直接丢：只有它匹配时回 not-visible，和「压根没匹配到」分开报
    if(!__vc_shown(el)){ hidden.push(el); continue; }
    if(kind==='exact') exact.push(el); else part.push(el);
  }
  var pool=exact.length?exact:part;
  if(!pool.length){
    if(hidden.length) return JSON.stringify({ok:false,reason:'not-visible',seen:all.length,candidates:hidden.length});
    return JSON.stringify({ok:false,reason:'not-found',seen:all.length});
  }
  // 视口内的优先，其次最内层；视口外的也留着，下面先滚过去再点
  pool.sort(function(a,b){
    var ia=__vc_inview(a)?1:0, ib=__vc_inview(b)?1:0;
    if(ia!==ib) return ib-ia;
    return __vc_depth(b)-__vc_depth(a);
  });
  var el=pool[0];
  if(el.disabled) return JSON.stringify({ok:false,reason:'disabled',candidates:pool.length});
  var scrolled=false;
  if(!__vc_inview(el)) scrolled=__vc_scroll(el);
  var desc={tag:el.tagName.toLowerCase(),label:__vc_label(el).slice(0,80),sel:__vc_sel(el)};
  try{ el.focus({preventScroll:true}); }catch(e){}
  try{ el.click(); }catch(e){ return JSON.stringify({ok:false,reason:'click-error',sel:desc.sel,scrolled:scrolled}); }
  return JSON.stringify({ok:true,tag:desc.tag,label:desc.label,sel:desc.sel,candidates:pool.length,scrolled:scrolled});
})(""" + q + """)
"""
    }

    private val SNAPSHOT_JS = """
(function(){
""" + HELPERS + """
  var all=document.querySelectorAll(__vc_query),out=[];
  for(var i=0;i<all.length&&out.length<80;i++){
    var el=all[i];
    if(!__vc_vis(el)) continue;
    var r=el.getBoundingClientRect();
    out.push({
      tag:el.tagName.toLowerCase(),
      text:__vc_label(el).slice(0,60),
      sel:__vc_sel(el),
      editable:!!(el.isContentEditable||el.tagName==='INPUT'||el.tagName==='TEXTAREA'),
      rect:[Math.round(r.left),Math.round(r.top),Math.round(r.width),Math.round(r.height)]
    });
  }
  return JSON.stringify({ok:true,items:out});
})()
"""

    /** 可交互元素 + 可见性/文字/选择器工具。所有查询共用一份，避免三份 JS 走样。 */
    private const val HELPERS = """
function __vc_inview(el){
  var r=el.getBoundingClientRect();
  return r.bottom>0&&r.top<window.innerHeight&&r.right>0&&r.left<window.innerWidth;
}
function __vc_shown(el){
  if(!el||!el.getBoundingClientRect) return false;
  var r=el.getBoundingClientRect();
  if(r.width<2||r.height<2) return false;
  // 自身 + 最近 3 层祖先：display:none 的祖先 rect 已经是 0，这里主要补 opacity:0（不继承）
  var n=el,guard=0;
  while(n&&n!==document.documentElement&&guard<3){
    var s=window.getComputedStyle(n);
    if(s.display==='none'||s.visibility==='hidden'||parseFloat(s.opacity||'1')===0) return false;
    n=n.parentElement;guard++;
  }
  return true;
}
function __vc_vis(el){ return __vc_shown(el)&&__vc_inview(el); }
function __vc_scroll(el){
  try{ el.scrollIntoView({block:'center',inline:'nearest'}); return true; }catch(e){ return false; }
}
function __vc_label(el){
  // aria-label / placeholder 是作者写的语义标签，优先且独占：不然按钮旁边的消息正文
  // 会被拼进来，按文字找「发送消息」会命中整段消息（2026-11-28 真机踩过）
  var a=(el.getAttribute('aria-label')||'').replace(/\s+/g,' ').trim();
  if(a) return a;
  var p=(el.getAttribute('placeholder')||'').replace(/\s+/g,' ').trim();
  if(p) return p;
  var t=el.innerText||el.textContent||'';
  return t.replace(/\s+/g,' ').trim();
}
function __vc_sel(el){
  if(el.id) return '#'+el.id;
  var a=el.getAttribute('aria-label');
  if(a) return el.tagName.toLowerCase()+'[aria-label="'+a.replace(/"/g,'')+'"]';
  for(var i=0;i<el.attributes.length;i++){
    var at=el.attributes[i];
    if(at.name.indexOf('data-')===0) return el.tagName.toLowerCase()+'['+at.name+'="'+at.value+'"]';
  }
  return el.tagName.toLowerCase();
}
function __vc_depth(el){var d=0;while(el&&el.parentElement){d++;el=el.parentElement}return d}
var __vc_query='button,a,input,textarea,select,[role="button"],[role="tab"],[role="link"],[contenteditable="true"],[data-pane],[data-row-key]';
// 点击候选：去掉 data-pane / data-row-key 这类布局容器——它们的 innerText 是整块内容，
// 按文字「包含」匹配会点到整条历史消息（2026-11-28 独立验证：空输入点中「发送消息 fix-ime-gaps…」）。
// 快照仍用全量 __vc_query，列表行要能显示出来。
var __vc_act_query='button,a,input,textarea,select,[role="button"],[role="tab"],[role="link"],[contenteditable="true"]';
"""
}
