package de.thegerman.simplesafe.ui.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.view.isVisible
import de.thegerman.simplesafe.R
import de.thegerman.simplesafe.repositories.SafeRepository.Safe
import de.thegerman.simplesafe.ui.base.BaseActivity
import de.thegerman.simplesafe.utils.asMiddleEllipsized
import de.thegerman.simplesafe.utils.copyToClipboard
import de.thegerman.simplesafe.utils.shiftedString
import kotlinx.android.synthetic.main.screen_main.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.koin.android.viewmodel.ext.android.viewModel
import pm.gnosis.crypto.utils.asEthereumAddressChecksumString

@ExperimentalCoroutinesApi
class MainActivity : BaseActivity<MainViewModelContract.State, MainViewModelContract>() {

    override val viewModel: MainViewModelContract by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.screen_main)

        main_retry_btn.setOnClickListener {
            viewModel.loadSafe()
        }

        main_invest_btn.setOnClickListener {
            viewModel.investAll()
        }
    }

    override fun updateState(state: MainViewModelContract.State) {
        state.safe?.address?.apply {
            val addressString = asEthereumAddressChecksumString()
            main_account_address.text = addressString.asMiddleEllipsized(4)
            main_account_img.setAddress(this)
            main_account_address.setOnLongClickListener {
                copyToClipboard("Wallet Address", addressString)
                true
            }
        }

        state.balances?.let { balances ->
            main_dai_balance_value.text = balances.daiBalance.shiftedString(18)
            main_cdai_balance_value.text = balances.cdaiBalance.shiftedString(18)
        }

        main_account_info_group.isVisible = state.safe != null
        when (val status = state.safe?.status) {
            Safe.Status.Ready -> {
                main_status.text = null
                main_balances_group.isVisible = true
                main_invest_btn.isVisible = true
                main_invest_btn.isEnabled = true
            }
            is Safe.Status.Unfunded -> {
                main_status.text = "Your Safe needs to be funded with ${status.paymentAmount.shiftedString(18)}"
                main_balances_group.isVisible = true
                main_invest_btn.isVisible = true
                main_invest_btn.isEnabled = false
            }
            else -> {
                main_status.text = "Your Safe is currently being deployed, please wait ..."
                main_balances_group.isVisible = !state.loading && !state.showRetry
                main_invest_btn.isVisible = false
                main_invest_btn.isEnabled = false
            }
        }
        main_invest_btn.isVisible = !state.submitting

        main_progress.isVisible = state.loading
        main_retry_btn.isVisible = state.showRetry
    }

    companion object {
        fun createIntent(context: Context) = Intent(context, MainActivity::class.java)
    }
}
