package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import keiyoushi.utils.runWebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.reflect.InvocationHandler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * Configures Android WebView's Default Profile to strip Android's injected `X-Requested-With`
 * header on `3xyaoi.com` proactively, so both manual `WebViewActivity` (globe icon) and
 * automatic `ChallengeInterceptor` passes avoid Cloudflare's hard WAF block ("Sorry, you have
 * been blocked") even before any OkHttp 403 occurs.
 */
internal object WebViewOriginHeader {

    private const val TAG = "XXXYaoiWebView"
    private const val FACTORY_CLASS = "org.chromium.support_lib_boundary.WebViewProviderFactoryBoundaryInterface"
    private const val PROVIDER_CLASS = "org.chromium.support_lib_boundary.WebViewProviderBoundaryInterface"
    private const val PROFILE_CLASS = "org.chromium.support_lib_boundary.ProfileBoundaryInterface"

    private val configuredOrigins = ConcurrentHashMap.newKeySet<String>()
    private val pending = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private class GlueMethods(
        val factoryHandler: InvocationHandler,
        val supportedFeatures: Array<String>,
        val createWebViewMethod: java.lang.reflect.Method,
        val getProfileMethod: java.lang.reflect.Method,
        val clearOriginMatchedHeaderMethod: java.lang.reflect.Method,
        val addOriginMatchedHeaderMethod: java.lang.reflect.Method,
    )

    @Volatile
    private var cachedGlue: GlueMethods? = null

    private fun resolveGlue(app: Application): GlueMethods? {
        cachedGlue?.let { return it }
        return runCatching {
            val webViewClassLoader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WebView.getWebViewClassLoader()
            } else {
                @SuppressLint("DiscouragedPrivateApi")
                WebView::class.java.getDeclaredMethod("getFactory")
                    .apply { isAccessible = true }
                    .invoke(null)!!.javaClass.classLoader!!
            }
            val util = Class.forName(
                "org.chromium.support_lib_glue.SupportLibReflectionUtil",
                false,
                webViewClassLoader,
            )
            val factoryHandler = util.getDeclaredMethod("createWebViewProviderFactory").invoke(null) as InvocationHandler

            // Resolve the declaring boundary interfaces from the host app / current classloader
            // so Chromium's boundary invocation dispatcher finds exact known interface classes.
            val loader = app.classLoader
            val factoryCls = loadBoundaryClass(loader, FACTORY_CLASS) ?: return null
            val providerCls = loadBoundaryClass(loader, PROVIDER_CLASS) ?: return null
            val profileCls = loadBoundaryClass(loader, PROFILE_CLASS) ?: return null

            val getFeaturesMethod = factoryCls.getMethod("getSupportedFeatures")

            @Suppress("UNCHECKED_CAST")
            val supportedFeatures = factoryHandler.invoke(null, getFeaturesMethod, null) as Array<String>
            GlueMethods(
                factoryHandler = factoryHandler,
                supportedFeatures = supportedFeatures,
                createWebViewMethod = factoryCls.getMethod("createWebView", WebView::class.java),
                getProfileMethod = providerCls.getMethod("getProfile"),
                clearOriginMatchedHeaderMethod = profileCls.getMethod(
                    "clearOriginMatchedHeader",
                    String::class.java,
                    String::class.java,
                ),
                addOriginMatchedHeaderMethod = profileCls.getMethod(
                    "addOriginMatchedHeader",
                    String::class.java,
                    String::class.java,
                    Set::class.java,
                ),
            ).also { cachedGlue = it }
        }.onFailure { t ->
            Log.w(TAG, "Glue resolution fallback needed: ${t.javaClass.simpleName}: ${t.message}")
        }.getOrNull()
    }

    private fun loadBoundaryClass(appLoader: ClassLoader, name: String): Class<*>? = runCatching { Class.forName(name, false, appLoader) }.getOrNull()
        ?: runCatching { Class.forName(name, false, WebViewOriginHeader::class.java.classLoader) }.getOrNull()

    fun originRulesFor(url: String?): Set<String> {
        if (url.isNullOrBlank()) return emptySet()
        val parsed = url.toHttpUrlOrNull() ?: return emptySet()
        if (parsed.scheme != "https" && parsed.scheme != "http") return emptySet()
        val origin = "${parsed.scheme}://${parsed.host}"
        return setOf(origin, "${parsed.scheme}://*.${parsed.host}")
    }

    fun ensure(baseUrl: String?) {
        val rules = originRulesFor(baseUrl)
        val exactOrigin = rules.firstOrNull { !it.contains('*') } ?: return
        if (exactOrigin in configuredOrigins) return
        val app = runCatching { Injekt.get<Application>() }.getOrNull() ?: return
        val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull() ?: return
        if (Looper.myLooper() == mainLooper) {
            applyOnMainThread(app, exactOrigin, rules)
        } else if (pending.compareAndSet(false, true)) {
            Handler(mainLooper).post {
                pending.set(false)
                applyOnMainThread(app, exactOrigin, rules)
            }
        }
    }

    private fun applyOnMainThread(app: Application, exactOrigin: String, rules: Set<String>) {
        if (exactOrigin in configuredOrigins) return
        val glue = resolveGlue(app)
        if (glue != null) {
            try {
                val features = glue.supportedFeatures.toHashSet()
                if ("MULTI_PROFILE" !in features || "CUSTOM_REQUEST_HEADERS" !in features) {
                    Log.w(TAG, "CUSTOM_REQUEST_HEADERS unsupported by current WebView")
                    return
                }
                val probe = WebView(app)
                try {
                    val providerHandler = glue.factoryHandler.invoke(
                        null,
                        glue.createWebViewMethod,
                        arrayOf<Any?>(probe),
                    ) as InvocationHandler
                    val profileHandler = providerHandler.invoke(
                        null,
                        glue.getProfileMethod,
                        null,
                    ) as InvocationHandler
                    profileHandler.invoke(
                        null,
                        glue.clearOriginMatchedHeaderMethod,
                        arrayOf<Any?>("X-Requested-With", ""),
                    )
                    try {
                        profileHandler.invoke(
                            null,
                            glue.addOriginMatchedHeaderMethod,
                            arrayOf<Any?>("X-Requested-With", "", rules),
                        )
                        configuredOrigins.addAll(rules)
                        Log.i(TAG, "Configured WebView X-Requested-With rule for $rules")
                    } catch (e: Throwable) {
                        val unwrapped = e.cause ?: e
                        if (unwrapped is IllegalArgumentException && rules.size > 1) {
                            profileHandler.invoke(
                                null,
                                glue.addOriginMatchedHeaderMethod,
                                arrayOf<Any?>("X-Requested-With", "", setOf(exactOrigin)),
                            )
                            configuredOrigins.add(exactOrigin)
                            Log.i(TAG, "Configured WebView X-Requested-With rule for [$exactOrigin]")
                        } else {
                            throw e
                        }
                    }
                    return
                } finally {
                    runCatching {
                        probe.stopLoading()
                        probe.destroy()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Direct profile header setup failed, falling back to core WebView", t)
            }
        }

        // Secondary fallback via core's runWebView (no network request made).
        scope.launch {
            runCatching {
                runWebView(timeout = 5.seconds) {
                    if (originMatchedHeaderSupported) {
                        setOriginMatchedHeader("X-Requested-With", "", rules)
                        configuredOrigins.addAll(rules)
                        Log.i(TAG, "Configured WebView X-Requested-With rule via core for $rules")
                    }
                    resolve(Unit)
                }
            }.onFailure { t ->
                Log.w(TAG, "Fallback WebView header setup failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }
}
