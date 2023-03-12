/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentCallbacks2
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.collection.LruCache
import com.hippo.Native
import com.hippo.beerbelly.SimpleDiskCache
import com.hippo.conaco.Conaco
import com.hippo.ehviewer.client.EhCookieStore
import com.hippo.ehviewer.client.EhDns
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhSSLSocketFactory
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.client.EhX509TrustManager
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.dao.buildMainDB
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.ui.CommonOperations
import com.hippo.ehviewer.ui.EhActivity
import com.hippo.image.ImageBitmap
import com.hippo.scene.SceneApplication
import com.hippo.util.ExceptionUtils
import com.hippo.util.ReadableTime
import com.hippo.util.launchIO
import com.hippo.yorozuya.FileUtils
import com.hippo.yorozuya.IntIdGenerator
import com.hippo.yorozuya.OSUtils
import java.io.File
import java.net.Proxy
import java.security.KeyStore
import java.util.Arrays
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.math.min
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import okhttp3.Cache
import okhttp3.OkHttpClient
import rikka.material.app.DayNightDelegate
import rikka.material.app.LocaleDelegate

class EhApplication : SceneApplication() {
    private val mIdGenerator = IntIdGenerator()
    private val mGlobalStuffMap = HashMap<Int, Any>()
    private val mActivityList = ArrayList<Activity>()
    val topActivity: EhActivity?
        get() = if (mActivityList.isNotEmpty()) {
            mActivityList[mActivityList.size - 1] as EhActivity
        } else {
            null
        }

    fun recreateAllActivity() {
        mActivityList.forEach { it.recreate() }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @SuppressLint("StaticFieldLeak")
    override fun onCreate() {
        application = this
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                if (Settings.saveCrashLog) {
                    Crash.saveCrashLog(e)
                }
            } catch (ignored: Throwable) {
            }
            handler?.uncaughtException(t, e)
        }
        super.onCreate()
        Native.initialize()
        GetText.initialize(this)
        Settings.initialize()
        ReadableTime.initialize(this)
        AppConfig.initialize(this)
        SpiderDen.initialize(this)
        LocaleDelegate.defaultLocale = Settings.locale
        DayNightDelegate.setApplicationContext(this)
        DayNightDelegate.setDefaultNightMode(Settings.theme)

        launchIO {
            launchIO {
                EhTagDatabase.update()
            }
            launchIO {
                ehDatabase
            }
            launchIO {
                DownloadManager.isIdle
            }
            launchIO {
                cleanupDownload()
            }
            launchIO {
                theDawnOfNewDay()
            }
        }
        mIdGenerator.setNextId(Settings.getInt(KEY_GLOBAL_STUFF_NEXT_ID, 0))
    }

    private fun cleanupDownload() {
        // Check no media file
        try {
            val downloadLocation = Settings.downloadLocation
            if (Settings.mediaScan) {
                CommonOperations.removeNoMediaFile(downloadLocation)
            } else {
                CommonOperations.ensureNoMediaFile(downloadLocation)
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            ExceptionUtils.throwIfFatal(t)
        }
        // Clear temp files
        try {
            clearTempDir()
        } catch (t: Throwable) {
            t.printStackTrace()
            ExceptionUtils.throwIfFatal(t)
        }
    }

    private suspend fun theDawnOfNewDay() {
        runCatching {
            if (Settings.requestNews && EhCookieStore.hasSignedIn()) {
                EhEngine.getNews(true)?.let { showEventPane(it) }
            }
        }.onFailure {
            it.printStackTrace()
        }
    }

    fun showEventPane(html: String) {
        if (Settings.hideHvEvents && html.contains("You have encountered a monster!")) {
            return
        }
        val activity = topActivity
        activity?.runOnUiThread {
            val dialog = AlertDialog.Builder(activity)
                .setMessage(Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY))
                .setPositiveButton(android.R.string.ok, null)
                .create()
            dialog.setOnShowListener {
                val messageView = dialog.findViewById<View>(android.R.id.message)
                if (messageView is TextView) {
                    messageView.movementMethod = LinkMovementMethod.getInstance()
                }
            }
            try {
                dialog.show()
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    private fun clearTempDir() {
        var dir = AppConfig.getTempDir()
        if (null != dir) {
            FileUtils.deleteContent(dir)
        }
        dir = AppConfig.getExternalTempDir()
        if (null != dir) {
            FileUtils.deleteContent(dir)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            conaco.beerBelly.clearMemory()
            galleryDetailCache.evictAll()
        }
    }

    fun putGlobalStuff(o: Any): Int {
        val id = mIdGenerator.nextId()
        mGlobalStuffMap[id] = o
        Settings.putInt(KEY_GLOBAL_STUFF_NEXT_ID, mIdGenerator.nextId())
        return id
    }

    fun containGlobalStuff(id: Int): Boolean {
        return mGlobalStuffMap.containsKey(id)
    }

    fun getGlobalStuff(id: Int): Any? {
        return mGlobalStuffMap[id]
    }

    fun removeGlobalStuff(id: Int): Any? {
        return mGlobalStuffMap.remove(id)
    }

    fun removeGlobalStuff(o: Any) {
        mGlobalStuffMap.values.removeAll(setOf(o))
    }

    fun registerActivity(activity: Activity) {
        mActivityList.add(activity)
    }

    fun unregisterActivity(activity: Activity) {
        mActivityList.remove(activity)
    }

    companion object {
        private const val KEY_GLOBAL_STUFF_NEXT_ID = "global_stuff_next_id"
        private const val DEBUG_CONACO = false

        @JvmStatic
        lateinit var application: EhApplication
            private set

        @JvmStatic
        val ehProxySelector by lazy { EhProxySelector() }

        @JvmStatic
        val nonCacheOkHttpClient by lazy {
            val builder = OkHttpClient.Builder()
                .cookieJar(EhCookieStore)
                .dns(EhDns)
                .proxySelector(ehProxySelector)

            if (Settings.dF) {
                var trustManager: X509TrustManager
                try {
                    val trustManagerFactory = TrustManagerFactory.getInstance(
                        TrustManagerFactory.getDefaultAlgorithm()
                    )
                    trustManagerFactory.init(null as KeyStore?)
                    val trustManagers = trustManagerFactory.trustManagers
                    check(!(trustManagers.size != 1 || trustManagers[0] !is X509TrustManager)) {
                        "Unexpected default trust managers:" + Arrays.toString(
                            trustManagers
                        )
                    }
                    trustManager = trustManagers[0] as X509TrustManager
                } catch (e: Exception) {
                    e.printStackTrace()
                    trustManager = EhX509TrustManager
                }

                builder.sslSocketFactory(EhSSLSocketFactory, trustManager)
                builder.proxy(Proxy.NO_PROXY)
            }
            builder.build()
        }

        // Never use this okhttp client to download large blobs!!!
        @JvmStatic
        val okHttpClient by lazy {
            nonCacheOkHttpClient.newBuilder()
                .cache(Cache(File(application.cacheDir, "http_cache"), 20L * 1024L * 1024L))
                .build()
        }

        @JvmStatic
        val conaco: Conaco<ImageBitmap> by lazy {
            val builder = Conaco.Builder<ImageBitmap>()
            builder.hasMemoryCache = true
            builder.memoryCacheMaxSize = min(20 * 1024 * 1024, OSUtils.getAppMaxMemory().toInt())
            builder.hasDiskCache = true
            builder.diskCacheDir = File(application.cacheDir, "thumb")
            builder.diskCacheMaxSize = Settings.thumbCacheSize * 1024 * 1024
            builder.okHttpClient = nonCacheOkHttpClient
            builder.objectHelper = ImageBitmapHelper()
            builder.debug = DEBUG_CONACO
            builder.build()
        }

        @JvmStatic
        val galleryDetailCache by lazy {
            LruCache<Long, GalleryDetail>(25).also {
                favouriteStatusRouter.addListener { gid, slot ->
                    it[gid]?.favoriteSlot = slot
                }
            }
        }

        @JvmStatic
        val spiderInfoCache by lazy {
            SimpleDiskCache(
                File(application.cacheDir, "spider_info"),
                20 * 1024 * 1024
            )
        }

        @JvmStatic
        val hosts by lazy { Hosts(application, "hosts.db") }

        @JvmStatic
        val favouriteStatusRouter by lazy { FavouriteStatusRouter() }

        @JvmStatic
        val ehDatabase by lazy { buildMainDB(application) }
    }
}