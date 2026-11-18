package com.simplecalculator.app

import android.app.Application
import androidx.work.WorkManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class App : Application(), ImageLoaderFactory {

    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
        WorkManager.getInstance(this)
        // After an app upgrade, retry sources that were paused by the previous
        // build. A new certificate/network fix must not inherit a 30-minute
        // pause list, so this happens before the first feed can read it.
        val prefs = getSharedPreferences("calc_state", MODE_PRIVATE)
        if (prefs.getInt("health_version", 0) != BuildConfig.VERSION_CODE) {
            container.health.resetAll(container.sources.all())
            prefs.edit().putInt("health_version", BuildConfig.VERSION_CODE).apply()
        }
        // Off the main thread: unpack the bundled speech model on first run.
        Thread {
            runCatching { kotlinx.coroutines.runBlocking { container.modelStore.ensureBundled() } }
        }.start()
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { container.http }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("thumbs"))
                // Thumbnails are tiny; a big disk cache saves a lot of mobile data.
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        // Sites send no-store/no-cache on hotlinked thumbs; ignoring that keeps
        // the cache useful on slow or flaky connections.
        .respectCacheHeaders(false)
        .crossfade(120)
        .build()
}
