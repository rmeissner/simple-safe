package de.thegerman.simplesafe.ui.join

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import androidx.lifecycle.liveData
import com.google.android.gms.auth.api.credentials.Credential
import com.google.android.gms.auth.api.credentials.Credentials
import com.google.android.gms.auth.api.credentials.CredentialsClient
import com.google.android.gms.auth.api.credentials.CredentialsOptions
import com.google.android.gms.common.api.ResolvableApiException
import de.thegerman.simplesafe.R
import de.thegerman.simplesafe.repositories.SafeRepository
import de.thegerman.simplesafe.ui.base.BaseActivity
import de.thegerman.simplesafe.ui.base.BaseViewModel
import de.thegerman.simplesafe.ui.base.LoadingViewModel
import de.thegerman.simplesafe.ui.main.MainActivity
import kotlinx.android.synthetic.main.screen_join.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.koin.androidx.viewmodel.ext.android.viewModel
import pm.gnosis.crypto.utils.asEthereumAddressChecksumString
import pm.gnosis.utils.asEthereumAddress

@ExperimentalCoroutinesApi
abstract class JoinContract : LoadingViewModel<JoinContract.State>() {

    abstract fun joinSafe(credentialsClient: CredentialsClient, input: String)
    abstract fun handleCredentialsStored()

    data class State(val loading: Boolean, val setup: Boolean, val recoverError: Exception?, override var viewAction: ViewAction?) :
        BaseViewModel.State
}

@ExperimentalCoroutinesApi
class JoinViewModel(
    private val context: Context,
    private val safeRepository: SafeRepository
): JoinContract() {

    override fun onLoadingError(state: State, e: Throwable) = state.copy(loading = false)

    override val state = liveData {
        for (state in stateChannel.openSubscription()) emit(state)
    }

    override fun joinSafe(credentialsClient: CredentialsClient, input: String) {
        loadingLaunch {
            updateState { copy(loading = true) }
            val address = input.asEthereumAddress() ?: throw IllegalArgumentException("Invalid account address provided")
            require(safeRepository.validateSafe(address)) { "Unsupported account provided" }
            val safe = safeRepository.joinSafe(address)
            val mnemonic = safeRepository.loadMnemonic()
            val credential = Credential.Builder(safe.address.asEthereumAddressChecksumString())
                .setName("Identify for ${context.getString(R.string.app_name)}")
                .setPassword(mnemonic)
                .build()
            credentialsClient.save(credential).addOnCompleteListener {
                if (it.isSuccessful) {
                    handleCredentialsStored()
                } else {
                    loadingLaunch {
                        updateState { copy(recoverError = it.exception!!) }
                        updateState { copy(recoverError = null, loading = false) }
                    }
                }
            }
        }
    }

    override fun handleCredentialsStored() {
        loadingLaunch {
            updateState { copy(setup = true) }
        }
    }

    override fun initialState() = State(false, false, null, null)
}

@ExperimentalCoroutinesApi
class JoinActivity: BaseActivity<JoinContract.State, JoinContract>() {
    override val viewModel: JoinContract by viewModel()

    private val credentialsOptions = CredentialsOptions.Builder().forceEnableSaveDialog().build()
    private lateinit var credentialsClient: CredentialsClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        credentialsClient = Credentials.getClient(this, credentialsOptions)
        setContentView(R.layout.screen_join)
        join_back_btn.setOnClickListener { onBackPressed() }
        join_submit_btn.setOnClickListener {
            viewModel.joinSafe(credentialsClient, join_address_input.text.toString())
        }
    }

    override fun updateState(state: JoinContract.State) {
        join_submit_btn.isEnabled = !state.loading
        if (state.setup) {
            startActivity(MainActivity.createIntent(this).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP })
            finish()
        }
        when (val error = state.recoverError) {
            is ResolvableApiException -> {
                try {
                    error.startResolutionForResult(this, RC_WRITE)
                } catch (e: IntentSender.SendIntentException) {
                    e.printStackTrace()
                    viewModel.handleCredentialsStored()
                }
            }
            is Exception -> {
                error.printStackTrace()
                viewModel.handleCredentialsStored()
            }
        }
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_WRITE) {
            viewModel.handleCredentialsStored()
        }
    }


    companion object {
        private const val RC_WRITE = 12365
        fun createIntent(context: Context) = Intent(context, JoinActivity::class.java)
    }
}