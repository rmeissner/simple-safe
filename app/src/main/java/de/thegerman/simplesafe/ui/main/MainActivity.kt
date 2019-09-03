package de.thegerman.simplesafe.ui.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import de.thegerman.simplesafe.BuildConfig
import de.thegerman.simplesafe.R
import de.thegerman.simplesafe.repositories.SafeRepository.Safe
import de.thegerman.simplesafe.ui.account.AccountActivity
import de.thegerman.simplesafe.ui.base.BaseActivity
import de.thegerman.simplesafe.ui.deposit.DepositActivity
import de.thegerman.simplesafe.ui.withdraw.WithdrawActivity
import de.thegerman.simplesafe.utils.shiftedString
import kotlinx.android.synthetic.main.screen_main.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.koin.android.viewmodel.ext.android.viewModel
import pm.gnosis.svalinn.common.utils.openUrl

@ExperimentalCoroutinesApi
class MainActivity : BaseActivity<MainViewModelContract.State, MainViewModelContract>() {

    override val viewModel: MainViewModelContract by viewModel()

    private val withdrawClickListener = View.OnClickListener { startActivity(WithdrawActivity.createIntent(this@MainActivity)) }
    private val depositClickListener = View.OnClickListener { startActivity(DepositActivity.createIntent(this@MainActivity)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.screen_main)

        main_retry_btn.setOnClickListener {
            viewModel.loadSafe()
        }
        main_withdraw_img.setOnClickListener(withdrawClickListener)
        main_withdraw_txt.setOnClickListener(withdrawClickListener)
        main_deposit_img.setOnClickListener(depositClickListener)
        main_deposit_txt.setOnClickListener(depositClickListener)
        main_account_img.setOnClickListener { startActivity(AccountActivity.createIntent(this)) }
    }

    override fun onResume() {
        super.onResume()
        viewModel.updatePendingTxState()
    }

    override fun updateState(state: MainViewModelContract.State) {
        state.safe?.address?.apply {
            main_account_img.setAddress(this)
        }

        state.balances?.let { balances ->
            main_pending_txt.text = "Pending $${balances.daiBalance.shiftedString(18, decimalsToDisplay = 2)}"
            val balanceParts = balances.cdaiBalance.shiftedString(18, decimalsToDisplay = 10).split(".")
            main_balance_txt.text = "$${balanceParts.first()}"
            main_balance_decimals_txt.text = ".${balanceParts.getOrNull(1) ?: "00"}"
            val interestParts = (balances.cdaiBalance - balances.referenceBalance).shiftedString(18, decimalsToDisplay = 10).split(".")
            main_interest_txt.text = "$${interestParts.first()}"
            main_interest_decimals_txt.text = ".${interestParts.getOrNull(1) ?: "00"}"
        }

        main_account_info_group.isVisible = state.safe != null
        when (val status = state.safe?.status) {
            Safe.Status.Ready -> {
                main_creation_status_txt.text = null
                main_balances_group.isVisible = true
            }
            is Safe.Status.Unfunded -> {
                main_creation_status_txt.text = "Your Safe needs to be funded with ${status.paymentAmount.shiftedString(18)}"
                main_balances_group.isVisible = true
            }
            else -> {
                main_creation_status_txt.text = "Your Safe is currently being deployed, please wait ..."
                main_balances_group.isVisible = !state.loading && !state.showRetry
            }
        }

        main_progress.isVisible = state.loading
        main_retry_btn.isVisible = state.showRetry
        main_status_progress.isVisible = state.txHash != null
        main_status_txt.isVisible = state.txHash != null
        main_status_txt.setOnClickListener {
            openUrl(BuildConfig.BLOCK_EXPLORER_TX.format(state.txHash))
        }
    }

    companion object {
        fun createIntent(context: Context) = Intent(context, MainActivity::class.java)
    }
}
