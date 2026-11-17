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
        // Ship-to-disk copy of the bundled speech model, off the main thread.
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
