package de.thegerman.simplesafe.ui.main

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Observer
import de.thegerman.simplesafe.R
import de.thegerman.simplesafe.repositories.SafeRepository.Safe
import de.thegerman.simplesafe.utils.asMiddleEllipsized
import de.thegerman.simplesafe.utils.copyToClipboard
import de.thegerman.simplesafe.utils.shiftedString
import kotlinx.android.synthetic.main.screen_main.*
import org.koin.android.viewmodel.ext.android.viewModel
import pm.gnosis.crypto.utils.asEthereumAddressChecksumString

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModelContract by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.screen_main)

        main_retry_btn.setOnClickListener {
            viewModel.loadSafe()
        }

        main_invest_btn.setOnClickListener {
            viewModel.investAll()
        }

        viewModel.state.observe(this, Observer {
            it.safe?.address?.apply {
                val addressString = asEthereumAddressChecksumString()
                main_account_address.text = addressString.asMiddleEllipsized(4)
                main_account_img.setAddress(this)
                main_account_address.setOnLongClickListener {
                    copyToClipboard("Wallet Address", addressString)
                    true
                }
            }

            it.balances?.let { balances ->
                main_dai_balance_value.text = balances.daiBalance.shiftedString(18)
                main_cdai_balance_value.text = balances.cdaiBalance.shiftedString(18)
            }

            main_account_info_group.isVisible = it.safe != null
            when (val status = it.safe?.status) {
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
                    main_balances_group.isVisible = !it.loading && !it.showRetry
                    main_invest_btn.isVisible = false
                    main_invest_btn.isEnabled = false
                }
            }
            main_invest_btn.isVisible = !it.submitting

            main_progress.isVisible = it.loading
            main_retry_btn.isVisible = it.showRetry

            it.viewAction?.let { update -> performAction(update) }
        })
    }

    private fun performAction(viewAction: MainViewModelContract.ViewAction) {
        when (viewAction) {
            is MainViewModelContract.ViewAction.ShowToast -> {
                Toast.makeText(this, viewAction.message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
