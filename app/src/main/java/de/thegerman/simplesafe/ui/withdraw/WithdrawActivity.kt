package de.thegerman.simplesafe.ui.withdraw

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import androidx.core.view.isVisible
import androidx.lifecycle.liveData
import de.thegerman.simplesafe.BuildConfig
import de.thegerman.simplesafe.Compound
import de.thegerman.simplesafe.MultiSend
import de.thegerman.simplesafe.R
import de.thegerman.simplesafe.repositories.SafeRepository
import de.thegerman.simplesafe.ui.base.BaseActivity
import de.thegerman.simplesafe.ui.base.BaseViewModel
import de.thegerman.simplesafe.utils.shiftedString
import kotlinx.android.synthetic.main.screen_withdraw.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.koin.androidx.viewmodel.ext.android.viewModel
import pm.gnosis.model.Solidity
import pm.gnosis.model.SolidityBase
import pm.gnosis.svalinn.common.utils.getColorCompat
import pm.gnosis.utils.asEthereumAddress
import pm.gnosis.utils.hexStringToByteArray
import java.math.BigDecimal
import java.math.BigInteger

@ExperimentalCoroutinesApi
abstract class WithdrawViewModelContract : BaseViewModel<WithdrawViewModelContract.State>() {
    abstract fun updateReceipient(input: String)
    abstract fun updateValue(input: String)
    abstract fun submit()
    data class State(
        val receipient: Solidity.Address?,
        val receipientError: String?,
        val value: BigInteger?,
        val valueError: String?,
        val fee: BigInteger?,
        val feeError: String?,
        val balance: BigInteger?,
        val canSubmit: Boolean,
        val submitting: Boolean,
        val txHash: String?,
        override var viewAction: ViewAction?
    ) : BaseViewModel.State
}

@ExperimentalCoroutinesApi
class WithdrawViewModel(
    private val safeRepository: SafeRepository
) : WithdrawViewModelContract() {

    override val state = liveData {
        safeLaunch { monitorBalances() }
        for (state in stateChannel.openSubscription()) emit(state)
    }

    override fun initialState() = State(null, null, null, null, null, null, null, false, false, null, null)

    private suspend fun monitorBalances() {
        // Check balances continuously
        while (true) {
            try {
                Log.d("#####", "Check balances")
                updateBalances()
            } catch (e: Exception) {
                Log.e("#####", "Check balances error: $e")
            }
            delay(15000)
        }
    }

    private suspend fun updateBalances(): SafeRepository.SafeBalances {
        val balances = safeRepository.loadSafeBalances()
        with(currentState()) {
            val canSubmit = value != null && receipient != null && balance != null && fee != null && fee + value <= balances.cdaiBalance
            updateState { copy(balance = balances.cdaiBalance, canSubmit = canSubmit) }
        }
        return balances
    }

    override fun updateReceipient(input: String) {
        safeLaunch {
            input.asEthereumAddress()?.let{
                updateState { copy(receipient = it, receipientError = null) }
                updateFees()
            } ?: run {
                updateState { copy(receipient = null, receipientError = if (input.isBlank()) null else "Invalid Ethereum address!") }
            }
        }
    }

    override fun updateValue(input: String) {
        safeLaunch {
            input.toBigDecimalOrNull()?.let{
                val amount = it.multiply(BigDecimal(10).pow(18)).toBigInteger()
                updateState { copy(value = amount, valueError = null) }
                updateFees()
            } ?: run {
                updateState { copy(value = null, valueError = if (input.isBlank()) null else "Invalid amount value!") }
            }
        }
    }

    private var feesJob: Job? = null
    private fun updateFees() {
        feesJob?.cancel()
        feesJob = safeLaunch {
            updateState { copy(fee = null, canSubmit = false) }
            with(currentState()) {
                val tx = buildWithdrawTx(value ?: BigInteger.ZERO, BigInteger.ZERO, receipient ?: MAX_ADDRESS)
                val execInfo = safeRepository.safeTransactionExecInfo(tx)
                val fees = (execInfo.baseGas + execInfo.txGas) * execInfo.gasPrice
                val canSubmit = value != null && receipient != null && balance != null && fees + value <= balance
                updateState { copy(fee = fees, canSubmit = canSubmit) }
            }
        }
    }

    override fun submit() {
        safeLaunch {
            try {
                updateState { copy(submitting = true) }
                with(currentState()) {
                    val tx = buildWithdrawTx(value!!, BigInteger.ZERO, receipient!!)
                    val execInfo = safeRepository.safeTransactionExecInfo(tx).let {
                        it.copy(baseGas = it.baseGas + BigInteger.valueOf(1000))
                    }
                    val fees = (execInfo.baseGas + execInfo.txGas) * execInfo.gasPrice
                    check(balance != null && fees + value <= balance) { "Cannot submit transaction" }
                    val txHash = safeRepository.submitSafeTransaction(buildWithdrawTx(value, fees, receipient), execInfo)
                    safeRepository.removeFromReferenceBalance(fees + value)
                    updateState { copy(txHash = txHash) }
                }
            } finally {
                updateState { copy(submitting = false) }
            }
        }
    }

    private fun buildWithdrawTx(amount: BigInteger, fees: BigInteger, receiver: Solidity.Address) =
        SafeRepository.SafeTx(
            BuildConfig.MULTI_SEND_ADDRESS.asEthereumAddress()!!,
            BigInteger.ZERO,
            buildWithdrawData(amount, fees, receiver),
            SafeRepository.SafeTx.Operation.DELEGATE
        )

    private fun buildWithdrawData(amount: BigInteger, fees: BigInteger, receiver: Solidity.Address) =
        MultiSend.MultiSend.encode(
            Solidity.Bytes(
                (SolidityBase.encodeFunctionArguments(
                    Solidity.UInt8(BigInteger.ZERO),
                    BuildConfig.CDAI_ADDRESS.asEthereumAddress()!!,
                    Solidity.UInt256(BigInteger.ZERO),
                    Solidity.Bytes(Compound.RedeemUnderlying.encode(Solidity.UInt256(amount + fees)).hexStringToByteArray())
                ) + SolidityBase.encodeFunctionArguments(
                    Solidity.UInt8(BigInteger.ZERO),
                    BuildConfig.DAI_ADDRESS.asEthereumAddress()!!,
                    Solidity.UInt256(BigInteger.ZERO),
                    Solidity.Bytes(
                        Compound.Transfer.encode(
                            receiver,
                            Solidity.UInt256(amount)
                        ).hexStringToByteArray()
                    )
                )).hexStringToByteArray()
            )
        )

    companion object {
        private val MAX_ADDRESS = "0xffffffffffffffffffffffffffffffffffffffff".asEthereumAddress()!!
    }
}

@ExperimentalCoroutinesApi
class WithdrawActivity : BaseActivity<WithdrawViewModelContract.State, WithdrawViewModelContract>() {
    override val viewModel: WithdrawViewModelContract by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.screen_withdraw)

        withdraw_back_btn.setOnClickListener { onBackPressed() }

        withdraw_address_input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(p0: Editable?) {}

            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}

            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
                viewModel.updateReceipient(withdraw_address_input.text.toString())
            }
        })

        withdraw_value_input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(p0: Editable?) {}

            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}

            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
                viewModel.updateValue(withdraw_value_input.text.toString())
            }
        })

        withdraw_submit_btn.setOnClickListener {
            viewModel.submit()
        }
    }

    override fun updateState(state: WithdrawViewModelContract.State) {
        withdraw_address_divider.setBackgroundColor(getColorCompat(if (state.receipientError != null) R.color.colorAccent else R.color.divider))
        withdraw_value_divider.setBackgroundColor(getColorCompat(if (state.valueError != null) R.color.colorAccent else R.color.divider))
        withdraw_balance_txt.text = state.balance?.let { "$${it.shiftedString(18)} available" } ?: "-"
        withdraw_fee_txt.text = state.fee?.let { "$${it.shiftedString(18)} fees" } ?: "-"
        withdraw_submit_btn.isVisible = state.canSubmit
        withdraw_submit_btn.isEnabled = !state.submitting
        state.txHash?.let { finish() }
    }

    companion object {
        fun createIntent(context: Context) = Intent(context, WithdrawActivity::class.java)
    }
}