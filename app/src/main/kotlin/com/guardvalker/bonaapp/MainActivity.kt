package com.guardvalker.bonaapp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast

/**
 * Cáscara mínima: abre la PWA publicada en GitHub Pages dentro de un WebView a pantalla completa.
 * Como carga la web online, cada deploy de la PWA ya es la nueva versión de la app; el APK solo
 * se republica cuando cambia el ícono, el nombre o esta cáscara.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val startUrl = BuildConfig.START_URL
    private val startHost = Uri.parse(startUrl).host

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = if (BuildConfig.ORIENTATION == "portrait")
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        web = WebView(this)
        val root = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.bar_color))
            addView(web, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // Edge-to-edge forzado desde Android 15: los márgenes de barras van como padding.
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or
                        android.view.WindowInsets.Type.ime() or android.view.WindowInsets.Type.displayCutout())
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                    // Consumidos: si el WebView también los recibiera, la página los sumaría de nuevo
                    // vía env(safe-area-inset-*) y quedaría espacio vacío arriba y abajo.
                    android.view.WindowInsets.CONSUMED
                } else {
                    @Suppress("DEPRECATION")
                    v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                    @Suppress("DEPRECATION")
                    insets.consumeSystemWindowInsets()
                }
            }
        }
        setContentView(root)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
        }
        web.setBackgroundColor(getColor(R.color.bar_color))
        CookieManager.getInstance().setAcceptCookie(true)
        web.addJavascriptInterface(BlobSaver(), "BonaAndroid")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "blob" || uri.scheme == "data") return false
                if ((uri.scheme == "https") && uri.host == startHost) return false
                // Todo lo demás (mailto:, wa.me, otros sitios) lo resuelve el sistema.
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                v: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                return try {
                    startActivityForResult(params.createIntent(), REQ_FILE); true
                } catch (e: Exception) { fileCallback = null; false }
            }
        }
        web.setDownloadListener { url, _, disposition, mime, _ ->
            val name = URLUtil.guessFileName(url, disposition, mime)
            if (url.startsWith("blob:")) {
                // Los blob: solo existen dentro de la página, se leen ahí y se pasan en base64.
                web.evaluateJavascript(
                    "(function(){fetch('$url').then(r=>r.blob()).then(b=>{var f=new FileReader();" +
                    "f.onloadend=function(){BonaAndroid.save(f.result.split(',')[1],'${name.replace("'", "")}','${mime ?: ""}')};" +
                    "f.readAsDataURL(b)})})()", null)
            } else if (url.startsWith("data:")) {
                val b64 = url.substringAfter("base64,", "")
                if (b64.isNotEmpty()) BlobSaver().save(b64, name, mime ?: "")
            }
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(startUrl)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        } else super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onPause() { super.onPause(); web.onPause(); CookieManager.getInstance().flush() }
    override fun onResume() { super.onResume(); web.onResume() }

    inner class BlobSaver {
        @JavascriptInterface
        fun save(b64: String, name: String, mime: String) {
            runCatching {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val type = mime.ifEmpty {
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "")) ?: "application/octet-stream"
                }
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, type)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                runOnUiThread { Toast.makeText(this@MainActivity, "Guardado en Descargas: $name", Toast.LENGTH_LONG).show() }
            }.onFailure {
                runOnUiThread { Toast.makeText(this@MainActivity, "No se pudo guardar el archivo", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private companion object { const val REQ_FILE = 1 }
}
