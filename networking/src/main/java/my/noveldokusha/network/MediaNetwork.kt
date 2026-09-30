package my.noveldokusha.network

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.network.interceptors.UserAgentInterceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MediaHttpClient

/**
 * Отдельный клиент для media3: БЕЗ скрейп-кэша 50МБ (сегменты HLS вытеснят
 * кэш скрапера), БЕЗ DecodeResponseInterceptor (ломает Range/Content-Length),
 * БЕЗ ретра-evict. Только UA-пресеты, куки и щадящие таймауты.
 * CF-interceptor НЕ добавлять до verify-point E2E (спека §5.2).
 */
@InstallIn(SingletonComponent::class)
@Module
object MediaNetworkModule {

    @Provides
    @Singleton
    @MediaHttpClient
    fun provideMediaOkHttpClient(
        // Тот же экземпляр cookieJar, что у скрейп-клиента (NetworkClient.bind в
        // NetworkingModule): куки CF/сессии общие, логика не дублируется.
        networkClient: NetworkClient,
        appPreferences: AppPreferences,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(networkClient.cookieJar)
            .addInterceptor(UserAgentInterceptor(appPreferences))
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()
}
