package de.thegerman.simplesafe.ui.join

import android.os.Bundle
import androidx.lifecycle.liveData
import com.google.android.gms.auth.api.credentials.Credentials
import com.google.android.gms.auth.api.credentials.CredentialsClient
import com.google.android.gms.auth.api.credentials.CredentialsOptions
import de.thegerman.simplesafe.R
import de.thegerman.simplesafe.ui.base.BaseActivity
import de.thegerman.simplesafe.ui.base.BaseViewModel
import kotlinx.android.synthetic.main.screen_join.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.koin.androidx.viewmodel.ext.android.viewModel

@ExperimentalCoroutinesApi
abstract class JoinContract: BaseViewModel<JoinContract.State>() {

    abstract fun joinSafe(input: String)

    data class State(val loading: Boolean, override var viewAction: ViewAction?): BaseViewModel.State
}

@ExperimentalCoroutinesApi
class JoinViewModel(

): JoinContract() {

    override val state = liveData {
        for (state in stateChannel.openSubscription()) emit(state)
    }

    override fun joinSafe(input: String) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun initialState() = State(false, null)
}

@ExperimentalCoroutinesApi
class JoinActivity: BaseActivity<JoinContract.State, JoinContract>() {
    override val viewModel: JoinContract by viewModel()

    private val credentialsOptions = CredentialsOptions.Builder().forceEnableSaveDialog().build()
    private lateinit var credentialsClient: CredentialsClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        credentialsClient = Credentials.getClient(this, credentialsOptions)
        setContentView(R.layout.screen_join)
        join_back_btn.setOnClickListener { onBackPressed() }
        join_submit_btn.setOnClickListener {
            viewModel.joinSafe(join_address_input.text.toString())
        }
    }

    override fun updateState(state: JoinContract.State) {
        join_submit_btn.isEnabled = !state.loading
    }

}