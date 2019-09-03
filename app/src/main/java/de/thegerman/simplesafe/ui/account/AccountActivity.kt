package de.thegerman.simplesafe.ui.account

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.liveData
import com.google.zxing.EncodeHintType
import de.thegerman.simplesafe.R
import de.thegerman.simplesafe.repositories.SafeRepository
import de.thegerman.simplesafe.ui.auto_top_up.AutoTopUpActivity
import de.thegerman.simplesafe.ui.base.BaseActivity
import de.thegerman.simplesafe.ui.base.BaseViewModel
import de.thegerman.simplesafe.utils.asMiddleEllipsized
import de.thegerman.simplesafe.utils.copyToClipboard
import de.thegerman.simplesafe.utils.generateQrCode
import kotlinx.android.synthetic.main.screen_account.*
import kotlinx.android.synthetic.main.screen_deposit.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.koin.android.viewmodel.ext.android.viewModel
import pm.gnosis.crypto.utils.asEthereumAddressChecksumString
import pm.gnosis.model.Solidity


@ExperimentalCoroutinesApi
class AccountActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.screen_account)

        account_back_btn.setOnClickListener { onBackPressed() }
        account_settings_txt.setOnClickListener { startActivity(AutoTopUpActivity.createIntent(this)) }
    }

    companion object {
        fun createIntent(context: Context) = Intent(context, AccountActivity::class.java)
    }
}
