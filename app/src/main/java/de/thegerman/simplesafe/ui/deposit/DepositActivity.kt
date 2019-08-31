package de.thegerman.simplesafe.ui.deposit

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.liveData
import com.google.zxing.EncodeHintType
import de.thegerman.simplesafe.R
import de.thegerman.simplesafe.repositories.SafeRepository
import de.thegerman.simplesafe.ui.base.BaseActivity
import de.thegerman.simplesafe.ui.base.BaseViewModel
import de.thegerman.simplesafe.utils.asMiddleEllipsized
import de.thegerman.simplesafe.utils.copyToClipboard
import de.thegerman.simplesafe.utils.generateQrCode
import kotlinx.android.synthetic.main.screen_deposit.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.koin.android.viewmodel.ext.android.viewModel
import pm.gnosis.crypto.utils.asEthereumAddressChecksumString
import pm.gnosis.model.Solidity

@ExperimentalCoroutinesApi
abstract class DepositViewModelContract : BaseViewModel<DepositViewModelContract.State>() {
    data class State(
        val qrCode: Bitmap?,
        val address: Solidity.Address?,
        val displayAddress: String?,
        override var viewAction: ViewAction?
    ) : BaseViewModel.State
}

@ExperimentalCoroutinesApi
class DepositViewModel(
    private val context: Context,
    private val safeRepository: SafeRepository
) : DepositViewModelContract() {

    override val state = liveData {
        safeLaunch { loadState() }
        for (state in stateChannel.openSubscription()) emit(state)
    }

    private suspend fun loadState() {
        val address = safeRepository.loadSafe().address
        val displayAddress = address.asEthereumAddressChecksumString()
        updateState { copy(address = address, displayAddress = displayAddress) }
        val qrCodeSize = context.resources.getDimension(R.dimen.qr_code_size).toInt()
        updateState { copy(qrCode = displayAddress.generateQrCode(qrCodeSize, qrCodeSize, options = mapOf(EncodeHintType.MARGIN to "0"))) }
    }

    override fun initialState() = State(null, null, null, null)
}

@ExperimentalCoroutinesApi
class DepositActivity : BaseActivity<DepositViewModelContract.State, DepositViewModelContract>() {
    override val viewModel: DepositViewModelContract by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.screen_deposit)

        deposit_back_btn.setOnClickListener { onBackPressed() }
    }

    override fun updateState(state: DepositViewModelContract.State) {
        deposit_address_img.isVisible = state.address != null
        deposit_in_arrow_img.isVisible = state.address != null
        deposit_address_txt.text = null
        state.address?.apply {
            deposit_address_img.setAddress(this)
        }
        deposit_copy_button_img.isVisible = state.displayAddress != null
        deposit_copy_button_txt.isVisible = state.displayAddress != null
        state.displayAddress?.apply {
            deposit_address_txt.text = asMiddleEllipsized(4)
            deposit_copy_button_img.setOnClickListener { copyAddress(this) }
            deposit_copy_button_txt.setOnClickListener { copyAddress(this) }
        }
        deposit_qr_progress.isVisible = state.qrCode == null
        deposit_qr_img.isVisible = state.qrCode != null
        state.qrCode?.let { deposit_qr_img.setImageBitmap(it) }
    }

    private fun copyAddress(addressString: String) {
        copyToClipboard("Wallet Address", addressString)
        Toast.makeText(this, "Copied address to clipboard", Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun createIntent(context: Context) = Intent(context, DepositActivity::class.java)
    }
}