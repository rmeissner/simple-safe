package de.thegerman.simplesafe.ui.account

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.liveData
import de.thegerman.simplesafe.R
import de.thegerman.simplesafe.repositories.SafeRepository
import de.thegerman.simplesafe.ui.transactions.pending.PendingTxActivity
import de.thegerman.simplesafe.ui.auto_top_up.AutoTopUpActivity
import de.thegerman.simplesafe.ui.base.BaseActivity
import de.thegerman.simplesafe.ui.base.BaseViewModel
import de.thegerman.simplesafe.ui.invite.InviteActivity
import de.thegerman.simplesafe.utils.asMiddleEllipsized
import de.thegerman.simplesafe.utils.copyToClipboard
import kotlinx.android.synthetic.main.screen_account.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.koin.androidx.viewmodel.ext.android.viewModel
import pm.gnosis.crypto.utils.asEthereumAddressChecksumString
import pm.gnosis.model.Solidity

@ExperimentalCoroutinesApi
abstract class AccountContract: BaseViewModel<AccountContract.State>() {
    data class State(val deviceId: Solidity.Address?, val formattedId: String?, override var viewAction: ViewAction?): BaseViewModel.State
}

@ExperimentalCoroutinesApi
class AccountViewModel(
    private val safeRepository: SafeRepository
): AccountContract() {
    override val state = liveData {
        loadDeviceData()
        for (event in stateChannel.openSubscription()) emit(event)
    }

    private fun loadDeviceData() {
        safeLaunch {
            val deviceId = safeRepository.loadDeviceId()
            val formattedId = deviceId.asEthereumAddressChecksumString()
            updateState { copy(deviceId = deviceId, formattedId = formattedId) }
        }
    }

    override fun initialState() = State(null, null, null)

}

@ExperimentalCoroutinesApi
class AccountActivity : BaseActivity<AccountContract.State, AccountContract>() {
    override val viewModel: AccountContract by viewModel()

    override fun updateState(state: AccountContract.State) {
        if(state.formattedId != null) {
            val clickListener = View.OnClickListener {
                copyToClipboard("Device Id", state.formattedId) {
                    Toast.makeText(this@AccountActivity, "Copied device id to clipboard!", Toast.LENGTH_SHORT).show()
                }
            }
            account_device_id_img.setOnClickListener(clickListener)
            account_device_id_txt.setOnClickListener(clickListener)
        }
        account_device_id_txt.text = state.formattedId?.asMiddleEllipsized(4)
        account_device_id_img.setAddress(state.deviceId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.screen_account)

        account_back_btn.setOnClickListener { onBackPressed() }
        account_settings_txt.setOnClickListener { startActivity(AutoTopUpActivity.createIntent(this)) }
        account_add_device_txt.setOnClickListener { startActivity(InviteActivity.createIntent(this)) }
        account_pending_txs_txt.setOnClickListener { startActivity(PendingTxActivity.createIntent(this)) }
    }

    companion object {
        fun createIntent(context: Context) = Intent(context, AccountActivity::class.java)
    }
}
