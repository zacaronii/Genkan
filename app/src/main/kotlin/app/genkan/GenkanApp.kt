package app.genkan

import android.app.Application
import app.genkan.romm.RomMImageAuthInterceptor
import app.genkan.romm.RomMSession
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class GenkanApp : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var rommSession: RomMSession

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val client = okHttpClient.newBuilder()
            .addInterceptor(RomMImageAuthInterceptor(rommSession::credentials))
            .build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .build()
    }
}
