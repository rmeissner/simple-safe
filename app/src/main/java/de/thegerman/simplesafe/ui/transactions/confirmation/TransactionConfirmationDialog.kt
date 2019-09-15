package de.thegerman.simplesafe.ui.transactions.confirmation

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import de.thegerman.simplesafe.R
import de.thegerman.simplesafe.repositories.SafeRepository
import de.thegerman.simplesafe.ui.base.BaseViewModel
import de.thegerman.simplesafe.ui.base.LoadingViewModel
import de.thegerman.simplesafe.utils.shiftedString
import kotlinx.android.synthetic.main.screen_confirm_tx.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.koin.android.ext.android.getKoin
import org.koin.androidx.viewmodel.ViewModelParameters
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.androidx.viewmodel.getViewModel
import org.koin.core.parameter.parametersOf
import pm.gnosis.model.Solidity
import java.math.BigInteger

@ExperimentalCoroutinesApi
abstract class TransactionConfirmationContract : LoadingViewModel<TransactionConfirmationContract.State>() {
    abstract fun confirmTransaction()
    data class State(val loading: Boolean, val fees: BigInteger?, val txHash: String?, override var viewAction: ViewAction?) : BaseViewModel.State
}

@ExperimentalCoroutinesApi
class TransactionConfirmationViewModel(
    private val transaction: SafeRepository.SafeTx,
    private val executionInfo: SafeRepository.SafeTxExecInfo?,
    private val confirmations: List<Pair<Solidity.Address, String?>>?,
    private val safeRepository: SafeRepository
) : TransactionConfirmationContract() {

    override val state = liveData {
        loadFees()
        for (event in stateChannel.openSubscription()) emit(event)
    }

    private suspend fun loadExecutionInfo() =
        executionInfo ?: run {
            safeRepository.safeTransactionExecInfo(transaction)
        }

    private fun loadFees() {
        safeLaunch {
            val execInfo = loadExecutionInfo()
            updateState { copy(fees = execInfo.fees) }
        }
    }

    override fun confirmTransaction() {
        if (currentState().loading) return
        loadingLaunch {
            updateState { copy(loading = true) }
            safeRepository.getPendingTransactionHash()?.let { throw IllegalStateException("Please wait until all actions are completed") }
            val execInfo = loadExecutionInfo()
            val hash = safeRepository.confirmSafeTransaction(transaction, execInfo, confirmations) ?: ""
            updateState { copy(loading = false, txHash = hash) }
        }
    }

    override fun onLoadingError(state: State, e: Throwable) = state.copy(loading = false)

    override fun initialState() = State(false, null, null, null)

}

@ExperimentalCoroutinesApi
class TransactionConfirmationDialog(
    activity: AppCompatActivity,
    transaction: SafeRepository.SafeTx,
    executionInfo: SafeRepository.SafeTxExecInfo? = null,
    confirmations: List<Pair<Solidity.Address, String?>>? = null
) : BottomSheetDialog(activity), LifecycleOwner, ViewModelStoreOwner {

    private val lifecycle = LifecycleRegistry(this)
    override fun getLifecycle() = lifecycle
    override fun getViewModelStore() = ViewModelStore()

    private val viewModel = activity.getKoin().getViewModel(
        ViewModelParameters(
            TransactionConfirmationContract::class, activity, null, { this },
            parameters = { parametersOf(transaction, executionInfo, confirmations) }
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.screen_confirm_tx)
        confirm_tx_submit_btn.setOnClickListener {
            viewModel.confirmTransaction()
        }
        viewModel.state.observe(this, Observer {
            confirm_tx_submit_btn.isEnabled = !it.loading
            if (it.txHash != null) dismiss()
            confirm_tx_fee_value.text = it.fees?.shiftedString(18)
        })
        lifecycle.currentState = Lifecycle.State.CREATED
        setOnDismissListener {
            lifecycle.currentState = Lifecycle.State.DESTROYED
        }
    }

    override fun onStop() {
        super.onStop()
        lifecycle.currentState = Lifecycle.State.CREATED
    }

    override fun onStart() {
        super.onStart()
        // IDK why this is required
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        lifecycle.currentState = Lifecycle.State.RESUMED
    }
}