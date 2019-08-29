package de.thegerman.simplesafe.ui.intro

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.credentials.Credential
import com.google.android.gms.auth.api.credentials.CredentialRequest
import com.google.android.gms.auth.api.credentials.CredentialsClient
import de.thegerman.simplesafe.R
import de.thegerman.simplesafe.repositories.SafeRepository
import de.thegerman.simplesafe.ui.base.BaseViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import pm.gnosis.crypto.utils.asEthereumAddressChecksumString
import pm.gnosis.utils.asEthereumAddress


@ExperimentalCoroutinesApi
abstract class IntroViewModelContract : BaseViewModel<IntroViewModelContract.State>() {
    data class State(
        val loading: Boolean,
        val setup: Boolean,
        val recoverError: RecoverError?,
        override var viewAction: ViewAction?
    ) : BaseViewModel.State

    data class RecoverError(val error: Exception, val type: RecoverType)

    enum class RecoverType {
        RECEIVE,
        STORE
    }

    abstract fun initNewAccount(credentialsClient: CredentialsClient)
    abstract fun credentialsRequest(credentialsClient: CredentialsClient)
    abstract fun handleCredentials(credential: Credential)
    abstract fun handleCredentialsStored()
}

@ExperimentalCoroutinesApi
class IntroViewModel(
    private val context: Context,
    private val safeRepository: SafeRepository
) : IntroViewModelContract() {

    private val loadingErrorHandler = CoroutineExceptionHandler { context, e ->
        viewModelScope.launch { updateState { copy(recoverError = null, loading = false) } }
        coroutineErrorHandler.handleException(context, e)
    }

    override fun initialState() = State(loading = false, setup = false, recoverError = null, viewAction = null)

    override val state: LiveData<State> = liveData {
        checkAppSate()
        for (state in stateChannel.openSubscription()) emit(state)
    }

    private fun checkAppSate() {
        loadingLaunch {
            updateState { copy(loading = true) }
            updateState { copy(loading = false, setup = safeRepository.isInitialized()) }
        }
    }

    private val credentialRequest = CredentialRequest.Builder()
        .setPasswordLoginSupported(true)
        .build()

    override fun credentialsRequest(credentialsClient: CredentialsClient) {
        loadingLaunch {
            updateState { copy(loading = true) }
            credentialsClient.request(credentialRequest).addOnCompleteListener {
                if (it.isSuccessful) {
                    handleCredentials(it.result!!.credential)
                } else {
                    loadingLaunch {
                        updateState { copy(recoverError = RecoverError(it.exception!!, RecoverType.RECEIVE)) }
                        updateState { copy(recoverError = null, loading = false) }
                    }
                }
            }
        }
    }

    override fun handleCredentials(credential: Credential) {
        loadingLaunch {
            safeRepository.recover(credential.id.asEthereumAddress()!!, credential.password!!)
            updateState { copy(setup = true) }
        }
    }

    override fun initNewAccount(credentialsClient: CredentialsClient) {
        loadingLaunch {
            updateState { copy(loading = true) }
            val safe = safeRepository.loadSafe()
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
                        updateState { copy(recoverError = RecoverError(it.exception!!, RecoverType.STORE)) }
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

    private fun loadingLaunch(block: suspend CoroutineScope.() -> Unit) {
        safeLaunch(loadingErrorHandler, block)
    }

}