package de.thegerman.simplesafe.ui.intro

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.widget.Toast
import androidx.core.view.isVisible
import com.google.android.gms.auth.api.credentials.Credential
import com.google.android.gms.auth.api.credentials.Credential.EXTRA_KEY
import com.google.android.gms.auth.api.credentials.Credentials
import com.google.android.gms.auth.api.credentials.CredentialsClient
import com.google.android.gms.auth.api.credentials.CredentialsOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import de.thegerman.simplesafe.R
import de.thegerman.simplesafe.ui.base.BaseActivity
import de.thegerman.simplesafe.ui.main.MainActivity
import kotlinx.android.synthetic.main.screen_intro.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.koin.android.viewmodel.ext.android.viewModel


@ExperimentalCoroutinesApi
class IntroActivity : BaseActivity<IntroViewModelContract.State, IntroViewModelContract>() {

    private val credentialsOptions = CredentialsOptions.Builder().forceEnableSaveDialog().build()
    private lateinit var credentialsClient: CredentialsClient

    override val viewModel: IntroViewModelContract by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        credentialsClient = Credentials.getClient(this, credentialsOptions)
        setContentView(R.layout.screen_intro)

        intro_new_account_btn.setOnClickListener {
            viewModel.initNewAccount(credentialsClient)
        }

        intro_recover_btn.setOnClickListener {
            viewModel.credentialsRequest(credentialsClient)
        }
    }

    override fun updateState(state: IntroViewModelContract.State) {
        intro_progress.isVisible = state.loading
        intro_new_account_btn.isVisible = !state.setup && !state.loading
        intro_recover_btn.isVisible = !state.setup && !state.loading
        if (state.setup) {
            startActivity(MainActivity.createIntent(this))
            finish()
        }
        when (val error = state.recoverError?.error) {
            is ResolvableApiException -> {
                try {
                    error.startResolutionForResult(this, state.recoverError.requestCode())
                } catch (e: IntentSender.SendIntentException) {
                    e.printStackTrace()
                    showCredentialsError()
                }
            }
            is ApiException -> {
                error.printStackTrace()
                showCredentialsError()
            }
        }
    }

    private fun IntroViewModelContract.RecoverError.requestCode() =
        when (type) {
            IntroViewModelContract.RecoverType.RECEIVE -> RC_READ
            IntroViewModelContract.RecoverType.STORE -> RC_WRITE
        }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_READ) {
            if (resultCode == Activity.RESULT_OK) {
                val credential = data!!.getParcelableExtra<Credential>(EXTRA_KEY)
                viewModel.handleCredentials(credential)
            } else {
                showCredentialsError()
            }
        } else if (requestCode == RC_WRITE) {
            if (resultCode == Activity.RESULT_OK) {
                viewModel.handleCredentialsStored()
            } else {
                showCredentialsError()
            }
        }
    }

    private fun showCredentialsError() {
        Toast.makeText(this, "Credential Read Failed", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val RC_READ = 12343
        private const val RC_WRITE = 12365
    }

}