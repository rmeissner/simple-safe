package de.thegerman.simplesafe.ui.auto_top_up

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.liveData
import de.thegerman.simplesafe.*
import de.thegerman.simplesafe.repositories.SafeRepository
import de.thegerman.simplesafe.ui.base.BaseActivity
import de.thegerman.simplesafe.ui.base.BaseViewModel
import de.thegerman.simplesafe.ui.base.LoadingViewModel
import de.thegerman.simplesafe.ui.transactions.confirmation.TransactionConfirmationDialog
import kotlinx.android.synthetic.main.screen_auto_top_up.*
import kotlinx.coroutines.*
import org.koin.androidx.viewmodel.ext.android.viewModel
import pm.gnosis.model.Solidity
import pm.gnosis.model.SolidityBase
import pm.gnosis.utils.asEthereumAddress
import pm.gnosis.utils.asEthereumAddressString
import pm.gnosis.utils.hexStringToByteArray
import java.math.BigInteger

@ExperimentalCoroutinesApi
abstract class AutoTopUpViewModelContract : BaseViewModel<AutoTopUpViewModelContract.State>() {
    abstract fun getTx(): SafeRepository.SafeTx
    data class State(
        val enabled: Boolean?,
        override var viewAction: ViewAction?
    ) : BaseViewModel.State
}

@ExperimentalCoroutinesApi
class AutoTopUpViewModel(
    private val safeRepository: SafeRepository
) : AutoTopUpViewModelContract() {

    override val state = liveData {
        safeLaunch { monitorBalances() }
        for (state in stateChannel.openSubscription()) emit(state)
    }

    override fun initialState() = State(null, null)

    private suspend fun monitorBalances() {

        val fee = BigInteger.valueOf(9).times(BigInteger.TEN.pow(16))
        Log.d("#####", "Execute to top-up: " +
                TopUpModule.ExecuteTopUp.encode(Solidity.UInt256(BigInteger.ZERO), Solidity.UInt256(fee))
        )
        updateState { initialState() }
        // Check balances continuously
        while (true) {
            try {
                Log.d("#####", "Check auto top up state")
                loadState()
            } catch (e: Exception) {
                Log.e("#####", "Check auto top up state error: $e")
            }
            delay(15000)
        }
    }

    private suspend fun loadState() {
        val topUpModule = safeRepository.loadModules().find {
            it.masterCopy == TOP_UP_ADDRESS
        }
        // TODO: check rules
        Log.d("#####", "Top up module at ${topUpModule?.address?.asEthereumAddressString()}")
        updateState { copy(enabled = topUpModule != null) }
    }

    override fun getTx() = buildEnableTx()

    private fun buildEnableTx(): SafeRepository.SafeTx {
        val initData = TopUpModule.Setup.encode(
            SolidityBase.Vector(
                listOf(
                    TopUpModule.TupleA(
                        BuildConfig.DAI_ADDRESS.asEthereumAddress()!!,
                        BuildConfig.CDAI_ADDRESS.asEthereumAddress()!!,
                        Solidity.UInt256(BigInteger.TEN.multiply(BigInteger.TEN.pow(18))),
                        Solidity.UInt256(BigInteger.TEN.pow(17)),
                        Solidity.UInt256(BigInteger.TEN.pow(18))
                    )
                )
            )
        )
        val proxyData = ProxyFactory.CreateProxy.encode(
            BuildConfig.TOP_UP_MODULE_ADDRESS.asEthereumAddress()!!,
            Solidity.Bytes(initData.hexStringToByteArray())
        )
        val setupData = Solidity.Bytes(proxyData.hexStringToByteArray()).encode()
        val data = CreateAndAddModules.CreateAndAddModules.encode(
            BuildConfig.PROXY_FACTORY_ADDRESS.asEthereumAddress()!!,
            Solidity.Bytes(setupData.hexStringToByteArray())
        )
        return SafeRepository.SafeTx(
            BuildConfig.CREATE_AND_ADD_MODULES_ADDRESS.asEthereumAddress()!!,
            BigInteger.ZERO,
            data,
            SafeRepository.SafeTx.Operation.DELEGATE
        )
    }

    companion object {
        private val TOP_UP_ADDRESS = BuildConfig.TOP_UP_MODULE_ADDRESS.asEthereumAddress()!!
    }

}

@ExperimentalCoroutinesApi
class AutoTopUpActivity : BaseActivity<AutoTopUpViewModelContract.State, AutoTopUpViewModelContract>() {
    override val viewModel: AutoTopUpViewModelContract by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.screen_auto_top_up)

        auto_top_up_back_btn.setOnClickListener { onBackPressed() }
    }

    override fun updateState(state: AutoTopUpViewModelContract.State) {
        auto_top_up_status_txt.setOnClickListener(null)
        when {
            state.enabled == null -> auto_top_up_status_txt.text = "Loading ..."
            state.enabled -> auto_top_up_status_txt.text = "Enabled"
            else -> {
                auto_top_up_status_txt.text = "Click to enable"
                auto_top_up_status_txt.setOnClickListener {
                    TransactionConfirmationDialog(this, viewModel.getTx()).show()
                }
            }
        }


    }

    companion object {
        fun createIntent(context: Context) = Intent(context, AutoTopUpActivity::class.java)
    }
}
