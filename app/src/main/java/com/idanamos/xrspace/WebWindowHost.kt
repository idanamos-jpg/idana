package com.idanamos.xrspace

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Intent
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/** Hosts a WebView on a private VirtualDisplay whose output feeds the window's SurfaceTexture. */
@SuppressLint("SetJavaScriptEnabled")
class WebWindowHost(private val act: MainActivity, private val win: XWindow, startUrl: String, launcher: Boolean, dpi: Int = DPI) {

    companion object {
        const val DPI = 260
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    private val presentation: Presentation
    val webView: WebView
    private var desktop = false
    private var mobileUa: String = ""

    init {
        val dm = act.getSystemService(DisplayManager::class.java)
        val vd = dm.createVirtualDisplay("xr-web-${win.id}", win.pxW, win.pxH, dpi, win.surface, 0)
        win.vd = vd
        presentation = Presentation(act, vd.display)
        webView = WebView(presentation.context)
        webView.setBackgroundColor(Color.WHITE)
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = false
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        if (launcher) {
            s.allowFileAccess = true
            webView.addJavascriptInterface(Bridge(), "XR")
        }
        mobileUa = s.userAgentString
        if (startUrl.contains("web.whatsapp.com")) setDesktop(true, reload = false)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val scheme = url.scheme ?: return false
                if (scheme == "http" || scheme == "https" || scheme == "file" || scheme == "about" || scheme == "data") return false
                try {
                    act.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
                return true
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                win.title = title ?: ""
            }
        }
        presentation.setContentView(webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        presentation.show()
        webView.loadUrl(startUrl)
    }

    private var touchDown = 0L
    private var lastX = 0f
    private var lastY = 0f

    private fun inject(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) touchDown = now
        val e = MotionEvent.obtain(touchDown, now, action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        webView.dispatchTouchEvent(e)
        e.recycle()
    }

    fun tap(x: Float, y: Float) {
        inject(MotionEvent.ACTION_DOWN, x, y)
        webView.postDelayed({ inject(MotionEvent.ACTION_UP, x, y) }, 40)
        // If a text field got focus, pop up the phone keyboard
        webView.postDelayed({
            webView.evaluateJavascript(
                "(function(){var e=document.activeElement;if(!e)return false;" +
                    "if(e.isContentEditable)return true;" +
                    "if(e.tagName==='TEXTAREA')return true;" +
                    "if(e.tagName==='INPUT'){var t=(e.type||'text').toLowerCase();" +
                    "return ['button','submit','checkbox','radio','range','color','file','reset','image','hidden'].indexOf(t)<0;}" +
                    "return false;})()"
            ) { r -> if (r == "true") act.showKeyboard() }
        }, 350)
    }

    fun dragStart(x: Float, y: Float) { lastX = x; lastY = y; inject(MotionEvent.ACTION_DOWN, x, y) }
    fun dragBy(dx: Float, dy: Float) { lastX += dx; lastY += dy; inject(MotionEvent.ACTION_MOVE, lastX, lastY) }
    fun dragEnd() { inject(MotionEvent.ACTION_UP, lastX, lastY) }

    fun back(): Boolean = if (webView.canGoBack()) { webView.goBack(); true } else false

    fun zoom(delta: Int) {
        webView.settings.textZoom = (webView.settings.textZoom + delta).coerceIn(50, 300)
    }

    fun setDesktop(on: Boolean, reload: Boolean = true) {
        desktop = on
        webView.settings.userAgentString = if (on) DESKTOP_UA else mobileUa
        if (reload) webView.reload()
    }

    fun toggleDesktop(): Boolean { setDesktop(!desktop); return desktop }

    fun openInput(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        webView.loadUrl(url)
    }

    fun typeText(text: String) {
        val js = """
            (function(t){
              var e=document.activeElement; if(!e) return;
              var ok=false; try{ ok=document.execCommand('insertText',false,t);}catch(x){}
              if(!ok){
                if(e.isContentEditable){ e.textContent+=t; }
                else if('value' in e){
                  var s=(e.selectionStart==null)?e.value.length:e.selectionStart;
                  var en=(e.selectionEnd==null)?e.value.length:e.selectionEnd;
                  e.value=e.value.slice(0,s)+t+e.value.slice(en);
                  try{e.selectionStart=e.selectionEnd=s+t.length;}catch(x){}
                }
                e.dispatchEvent(new Event('input',{bubbles:true}));
              }
            })(${JSONObject.quote(text)});
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    fun backspace(count: Int = 1) {
        if (count <= 0) return
        val js = """
            (function(n){ for(var i=0;i<n;i++){
              var e=document.activeElement; if(!e) return;
              var ok=false; try{ ok=document.execCommand('delete',false,null);}catch(x){}
              if(!ok && 'value' in e){
                var s=(e.selectionStart==null)?e.value.length:e.selectionStart;
                var en=(e.selectionEnd==null)?e.value.length:e.selectionEnd;
                if(s===en && s>0){ s=s-1; }
                e.value=e.value.slice(0,s)+e.value.slice(en);
                try{e.selectionStart=e.selectionEnd=s;}catch(x){}
                e.dispatchEvent(new Event('input',{bubbles:true}));
              }
            }})($count);
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    fun enter() {
        val js = """
            (function(){
              var e=document.activeElement; if(!e) return;
              ['keydown','keypress','keyup'].forEach(function(k){
                e.dispatchEvent(new KeyboardEvent(k,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));
              });
              if(e.tagName==='TEXTAREA'||e.isContentEditable){ try{document.execCommand('insertLineBreak');}catch(x){} return; }
              if(e.form){ if(e.form.requestSubmit) e.form.requestSubmit(); else e.form.submit(); }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    fun destroy() {
        try { presentation.dismiss() } catch (_: Exception) {}
        try { webView.destroy() } catch (_: Exception) {}
        try { win.vd?.release() } catch (_: Exception) {}
        win.vd = null
    }

    /** JavaScript bridge for the home screen (launcher.html). */
    inner class Bridge {
        @JavascriptInterface fun open(url: String) { act.runOnUiThread { act.openWeb(url) } }
        @JavascriptInterface fun mirror() { act.runOnUiThread { act.requestMirror() } }
        @JavascriptInterface fun apps(): String = act.appsJson()
        @JavascriptInterface fun icon(pkg: String): String = act.appIcon(pkg)
        @JavascriptInterface fun launch(pkg: String) { act.runOnUiThread { act.launchApp(pkg) } }
    }
}
