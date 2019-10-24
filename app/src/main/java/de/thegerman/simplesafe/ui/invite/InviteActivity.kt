package de.thegerman.simplesafe.ui.invite

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import de.thegerman.simplesafe.GnosisSafe
import de.thegerman.simplesafe.R
import de.thegerman.simplesafe.repositories.SafeRepository
import de.thegerman.simplesafe.ui.base.BaseActivity
import de.thegerman.simplesafe.ui.base.BaseViewModel
import de.thegerman.simplesafe.ui.base.LoadingViewModel
import de.thegerman.simplesafe.ui.transactions.confirmation.TransactionConfirmationDialog
import kotlinx.android.synthetic.main.screen_invite.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import pm.gnosis.model.Solidity
import pm.gnosis.utils.asEthereumAddress
import java.math.BigInteger

@ExperimentalCoroutinesApi
abstract class InviteContract : LoadingViewModel<InviteContract.State>() {

    abstract fun addDevice(input: String)

    data class State(val loading: Boolean, override var viewAction: ViewAction?) : BaseViewModel.State
}

@ExperimentalCoroutinesApi
class InviteViewModel(
    private val safeRepository: SafeRepository
) : InviteContract() {

    override val state = liveData {
        for (state in stateChannel.openSubscription()) emit(state)
    }

    override fun addDevice(input: String) {
        loadingLaunch {
            updateState { copy(loading = true) }
            safeRepository.getPendingTransactionHash()?.let { throw IllegalArgumentException("There is already a pending action") }
            val device = input.asEthereumAddress() ?: throw IllegalArgumentException("Invalid device ID provided!")
            val safeInfo = safeRepository.loadSafeInfo()
            require(!safeInfo.owners.contains(device)) { "Device is already part of the account" }
            val tx = SafeRepository.SafeTx(
                to = safeInfo.address,
                value = BigInteger.ZERO,
                data = GnosisSafe.AddOwnerWithThreshold.encode(device, Solidity.UInt256(safeInfo.threshold)),
                operation = SafeRepository.SafeTx.Operation.CALL
            )
            updateState { copy(loading = false, viewAction = ConfirmTx(tx)) }
        }
    }

    override fun onLoadingError(state: State, e: Throwable) = state.copy(loading = false)

    override fun initialState() = State(false, null)
}

@ExperimentalCoroutinesApi
class InviteActivity : BaseActivity<InviteContract.State, InviteContract>() {
    override val viewModel: InviteContract by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.screen_invite)

        invite_back_btn.setOnClickListener { onBackPressed() }

        invite_submit_btn.setOnClickListener {
            viewModel.addDevice(invite_address_input.text.toString())
        }
    }

    override fun updateState(state: InviteContract.State) {
        invite_submit_btn.isEnabled = !state.loading
    }

    companion object {
        fun createIntent(context: Context) = Intent(context, InviteActivity::class.java)
    }

}