package de.thegerman.simplesafe

import android.app.Application
import com.squareup.moshi.Moshi
import com.squareup.picasso.Picasso
import de.thegerman.simplesafe.data.JsonRpcApi
import de.thegerman.simplesafe.data.RelayServiceApi
import de.thegerman.simplesafe.data.adapter.*
import de.thegerman.simplesafe.repositories.SafeRepository
import de.thegerman.simplesafe.repositories.SafeRepositoryImpl
import de.thegerman.simplesafe.ui.deposit.DepositViewModel
import de.thegerman.simplesafe.ui.deposit.DepositViewModelContract
import de.thegerman.simplesafe.ui.intro.IntroViewModel
import de.thegerman.simplesafe.ui.intro.IntroViewModelContract
import de.thegerman.simplesafe.ui.main.MainViewModel
import de.thegerman.simplesafe.ui.main.MainViewModelContract
import de.thegerman.simplesafe.ui.withdraw.WithdrawViewModel
import de.thegerman.simplesafe.ui.withdraw.WithdrawViewModelContract
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.android.viewmodel.dsl.viewModel
import org.koin.core.context.startKoin
import org.koin.dsl.module
import pm.gnosis.mnemonic.Bip39
import pm.gnosis.mnemonic.Bip39Generator
import pm.gnosis.mnemonic.android.AndroidWordListProvider
import pm.gnosis.svalinn.common.PreferencesManager
import pm.gnosis.svalinn.security.EncryptionManager
import pm.gnosis.svalinn.security.FingerprintHelper
import pm.gnosis.svalinn.security.KeyStorage
import pm.gnosis.svalinn.security.impls.AesEncryptionManager
import pm.gnosis.svalinn.security.impls.AndroidFingerprintHelper
import pm.gnosis.svalinn.security.impls.AndroidKeyStorage
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class TraderApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // start Koin!
        startKoin {
            // Android context
            androidContext(this@TraderApplication)
            // modules
            modules(listOf(coreModule, apiModule, repositoryModule, viewModelModule))
        }
    }

    private val coreModule = module {

        single { Picasso.get() }

        single { OkHttpClient.Builder().build() }

        single {
            Moshi.Builder()
                .add(WeiAdapter())
                .add(HexNumberAdapter())
                .add(DecimalNumberAdapter())
                .add(DefaultNumberAdapter())
                .add(SolidityAddressAdapter())
                .build()
        }

        single<Bip39> { Bip39Generator(AndroidWordListProvider(get())) }

        single { PreferencesManager(get()) }

        single<KeyStorage> { AndroidKeyStorage(get()) }

        single<FingerprintHelper> { AndroidFingerprintHelper(get()) }

        single<EncryptionManager> { AesEncryptionManager(get(), get(), get(), get(), 4096) }
    }

    private val repositoryModule = module {
        single<SafeRepository> { SafeRepositoryImpl(get(), get(), get(), get(), get()) }
    }

    @ExperimentalCoroutinesApi
    private val viewModelModule = module {
        viewModel<MainViewModelContract> { MainViewModel(get()) }
        viewModel<IntroViewModelContract> { IntroViewModel(get(), get()) }
        viewModel<WithdrawViewModelContract> { WithdrawViewModel(get()) }
        viewModel<DepositViewModelContract> { DepositViewModel(get(), get()) }
    }

    private val apiModule = module {
        single<RelayServiceApi> {
            Retrofit.Builder()
                .client(get())
                .baseUrl(RelayServiceApi.BASE_URL)
                .addConverterFactory(MoshiConverterFactory.create(get()))
                .build()
                .create(RelayServiceApi::class.java)
        }

        single<JsonRpcApi> {
            val baseClient: OkHttpClient = get()
            val client = baseClient.newBuilder().addInterceptor {
                val request = it.request()
                val builder = request.url().newBuilder()
                val url = builder.addPathSegment(BuildConfig.INFURA_API_KEY).build()
                it.proceed(request.newBuilder().url(url).build())
            }.build()
            Retrofit.Builder()
                .client(client)
                .baseUrl(JsonRpcApi.BASE_URL)
                .addConverterFactory(MoshiConverterFactory.create(get()))
                .build()
                .create(JsonRpcApi::class.java)
        }
    }
}