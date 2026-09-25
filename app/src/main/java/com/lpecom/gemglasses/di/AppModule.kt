package com.lpecom.gemglasses.di

import android.content.Context
import android.util.Log
import com.lpecom.gemglasses.BuildConfig
import com.lpecom.gemglasses.glasses.GlassesBackend
import com.lpecom.gemglasses.glasses.mock.MockGlassesBackend
import com.lpecom.gemglasses.tools.VisionBridge
import com.lpecom.gemglasses.tools.VisionController
import com.lpecom.gemglasses.wakeword.VoskWakeWordEngine
import com.lpecom.gemglasses.wakeword.WakeWordEngine
import dagger.Module
import dagger.Provides
import dagger.Binds
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        // Live sockets are long-lived; disable the read timeout, keep pings alive.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Selects the glasses backend at runtime. The real Meta DAT backend is
     * loaded reflectively so `src/main` never references the SDK — that keeps
     * mock/CI builds compiling without a github_token.
     */
    @Provides
    @Singleton
    fun glassesBackend(@ApplicationContext context: Context): GlassesBackend {
        if (!BuildConfig.USE_REAL_GLASSES) return MockGlassesBackend()
        return runCatching {
            val cls = Class.forName("com.lpecom.gemglasses.glasses.real.RealGlassesBackend")
            cls.getConstructor(Context::class.java).newInstance(context) as GlassesBackend
        }.getOrElse {
            Log.w("AppModule", "Real glasses backend unavailable, using mock", it)
            MockGlassesBackend()
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindsModule {
    @Binds
    abstract fun visionController(bridge: VisionBridge): VisionController

    @Binds
    abstract fun wakeWordEngine(impl: VoskWakeWordEngine): WakeWordEngine
}
