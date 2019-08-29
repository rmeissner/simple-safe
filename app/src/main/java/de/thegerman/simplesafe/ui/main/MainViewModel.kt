package de.thegerman.simplesafe.ui.main

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import de.thegerman.simplesafe.BuildConfig
import de.thegerman.simplesafe.Compound
import de.thegerman.simplesafe.repositories.SafeRepository
import de.thegerman.simplesafe.ui.base.BaseViewModel
import de.thegerman.simplesafe.MultiSend
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
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
        override var viewAction: ViewAction?
    ): BaseViewModel.State

    abstract fun loadSafe()
    abstract fun investAll()
}

@ExperimentalCoroutinesApi
class MainViewModel(
    private val safeRepository: SafeRepository
) : MainViewModelContract() {
    override fun initialState() = State(loading = false, showRetry = false, safe = null, balances = null, submitting = false, viewAction = null)

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

                (currentState().safe?.status as? SafeRepository.Safe.Status.Unfunded)?.let {
                    if (it.paymentAmount <= balances.daiBalance) safeRepository.triggerSafeDeployment()
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
     * Investing
     */

    override fun investAll() {
        viewModelScope.launch(Dispatchers.IO + coroutineErrorHandler) {
            updateState { copy(submitting = true) }
            try {
                val balance = currentState().balances?.daiBalance ?: return@launch
                val amount = balance - BigInteger.ONE.multiply(BigInteger.TEN.pow(18))
                if (amount < BigInteger.ZERO) return@launch
                val approveData = Compound.Approve.encode(BuildConfig.CDAI_ADDRESS.asEthereumAddress()!!, Solidity.UInt256(amount))
                val mintData = Compound.Mint.encode(Solidity.UInt256(amount))
                val data = MultiSend.MultiSend.encode(
                    Solidity.Bytes(
                        (SolidityBase.encodeFunctionArguments(
                            Solidity.UInt8(BigInteger.ZERO),
                            BuildConfig.DAI_ADDRESS.asEthereumAddress()!!,
                            Solidity.UInt256(BigInteger.ZERO),
                            Solidity.Bytes(approveData.hexStringToByteArray())
                        ) + SolidityBase.encodeFunctionArguments(
                            Solidity.UInt8(BigInteger.ZERO),
                            BuildConfig.CDAI_ADDRESS.asEthereumAddress()!!,
                            Solidity.UInt256(BigInteger.ZERO),
                            Solidity.Bytes(mintData.hexStringToByteArray())
                        ))
                            .hexStringToByteArray()
                    )
                )
                val txHash = safeRepository.submitSafeTransaction(
                    BuildConfig.MULTI_SEND_ADDRESS.asEthereumAddress()!!,
                    BigInteger.ZERO,
                    data,
                    SafeRepository.SafeTxOperation.DELEGATE
                )
                Log.d("#####", "tx hash: $txHash")
            } finally {
                updateState { copy(submitting = false) }
            }
            updateBalances()
        }
    }
}