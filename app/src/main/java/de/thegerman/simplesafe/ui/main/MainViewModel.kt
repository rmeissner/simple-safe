package de.thegerman.simplesafe.ui.main

import android.util.Log
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import de.thegerman.simplesafe.BuildConfig
import de.thegerman.simplesafe.Compound
import de.thegerman.simplesafe.repositories.SafeRepository
import de.thegerman.simplesafe.ui.base.BaseViewModel
import de.thegerman.simplesafe.MultiSend
import kotlinx.coroutines.*
import pm.gnosis.model.Solidity
import pm.gnosis.model.SolidityBase
import pm.gnosis.utils.asEthereumAddress
import pm.gnosis.utils.hexStringToByteArray
import java.math.BigInteger

@ExperimentalCoroutinesApi
abstract class MainViewModelContract : BaseViewModel<MainViewModelContract.State>() {

    data class State(
        val loading: Boolean,
        val showRetry: Boolean,
        val safe: SafeRepository.Safe?,
        val balances: SafeRepository.SafeBalances?,
        val submitting: Boolean,
        val txHash: String?,
        override var viewAction: ViewAction?
    ) : BaseViewModel.State

    abstract fun loadSafe()
    abstract fun investAll()
    abstract fun updatePendingTxState()
}

@ExperimentalCoroutinesApi
class MainViewModel(
    private val safeRepository: SafeRepository
) : MainViewModelContract() {
    override fun initialState() =
        State(loading = false, showRetry = false, safe = null, balances = null, submitting = false, txHash = null, viewAction = null)

    override val state = liveData {
        loadSafe()
        for (state in stateChannel.openSubscription()) emit(state)
    }

    /*
     * Safe status
     */

    override fun loadSafe() {
        viewModelScope.launch(Dispatchers.IO + coroutineErrorHandler) {
            updateState { copy(loading = true, showRetry = false) }
            val safe: SafeRepository.Safe
            try {
                safe = safeRepository.loadSafe()
            } catch (e: Exception) {
                updateState { copy(loading = false, showRetry = true) }
                throw e
            }
            updateState { copy(loading = false, safe = safe) }
            launch { monitorState(safe) }
            launch { monitorBalances() }
            launch { monitorPendingTx() }
        }
    }

    private suspend fun monitorState(safe: SafeRepository.Safe) {
        // Check state continuously
        while (true) {
            try {
                Log.d("#####", "Check stats")
                val status = safeRepository.checkStatus()
                updateState { copy(safe = SafeRepository.Safe(safe.address, status)) }
                if (status == SafeRepository.Safe.Status.Ready) break
            } catch (e: Exception) {
                Log.e("#####", "Check stats error: $e")
            }
            delay(15000)
        }
    }

    private suspend fun monitorBalances() {
        // Check balances continuously
        while (true) {
            try {
                Log.d("#####", "Check balances")
                val balances = updateBalances()

                when (val status = currentState().safe?.status) {
                    is SafeRepository.Safe.Status.Unfunded -> {
                        if (status.paymentAmount <= balances.daiBalance) safeRepository.triggerSafeDeployment()
                    }
                    is SafeRepository.Safe.Status.Ready -> {
                        //if (balances.daiBalance >= ONE_DAI) investAll()
                    }
                }
            } catch (e: Exception) {
                Log.e("#####", "Check balances error: $e")
            }
            delay(15000)
        }
    }

    private suspend fun updateBalances(): SafeRepository.SafeBalances {
        val balances = safeRepository.loadSafeBalances()
        updateState { copy(balances = balances) }
        return balances
    }

    /*
     * Transactions
     */

    override fun updatePendingTxState() {
        safeLaunch {
            checkPendingTx()
        }
    }

    private suspend fun monitorPendingTx() {
        // Check balances continuously
        while (true) {
            try {
                Log.d("#####", "Check pending tx")
                checkPendingTx()
            } catch (e: Exception) {
                Log.e("#####", "Check pending tx error: $e")
            }
            delay(15000)
        }
    }

    private suspend fun checkPendingTx() {
        when (val txState = safeRepository.checkPendingTransaction()) {
            is SafeRepository.TxStatus.Pending ->
                updateState { copy(txHash = txState.hash) }
            is SafeRepository.TxStatus.Success -> {
                updateBalances()
                updateState { copy(txHash = null) }
            }
            is SafeRepository.TxStatus.Failed ->
                updateState { copy(txHash = null, viewAction = ViewAction.ShowToast("Transaction failed")) }
            else ->
                updateState { copy(txHash = null) }
        }
    }


    /*
     * Investing
     */

    override fun investAll() {
        viewModelScope.launch(Dispatchers.IO + coroutineErrorHandler) {
            updateState { copy(submitting = true) }
            try {
                currentState().txHash?.let { return@launch } // Transaction pending, do not perform another
                val balance = currentState().balances?.daiBalance ?: return@launch
                val amount = balance - ONE_DAI.div(BigInteger.valueOf(4)).times(BigInteger.valueOf(3))
                if (amount < BigInteger.ZERO) return@launch
                val execInfo = safeRepository.safeTransactionExecInfo(buildInvestTx(amount))
                val txHash = safeRepository.submitSafeTransaction(buildInvestTx(amount), execInfo)
                safeRepository.addToReferenceBalance(amount)
                updateState { copy(txHash = txHash) }
            } finally {
                updateState { copy(submitting = false) }
            }
            updateBalances()
        }
    }

    private fun buildInvestTx(amount: BigInteger) =
        SafeRepository.SafeTx(
            BuildConfig.MULTI_SEND_ADDRESS.asEthereumAddress()!!,
            BigInteger.ZERO,
            buildInvestData(amount),
            SafeRepository.SafeTx.Operation.DELEGATE
        )

    private fun buildInvestData(amount: BigInteger) =
        MultiSend.MultiSend.encode(
            Solidity.Bytes(
                (SolidityBase.encodeFunctionArguments(
                    Solidity.UInt8(BigInteger.ZERO),
                    BuildConfig.DAI_ADDRESS.asEthereumAddress()!!,
                    Solidity.UInt256(BigInteger.ZERO),
                    Solidity.Bytes(
                        Compound.Approve.encode(
                            BuildConfig.CDAI_ADDRESS.asEthereumAddress()!!,
                            Solidity.UInt256(amount)
                        ).hexStringToByteArray()
                    )
                ) + SolidityBase.encodeFunctionArguments(
                    Solidity.UInt8(BigInteger.ZERO),
                    BuildConfig.CDAI_ADDRESS.asEthereumAddress()!!,
                    Solidity.UInt256(BigInteger.ZERO),
                    Solidity.Bytes(Compound.Mint.encode(Solidity.UInt256(amount)).hexStringToByteArray())
                ))
                    .hexStringToByteArray()
            )
        )

    companion object {
        private val ONE_DAI = BigInteger.ONE.multiply(BigInteger.TEN.pow(18))
    }
}
