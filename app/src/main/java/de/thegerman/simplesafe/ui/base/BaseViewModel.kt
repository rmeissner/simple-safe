package de.thegerman.simplesafe.ui.base

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.thegerman.simplesafe.repositories.SafeRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ConflatedBroadcastChannel

@ExperimentalCoroutinesApi
abstract class BaseViewModel<T : BaseViewModel.State> : ViewModel() {
    abstract val state: LiveData<T>

    protected abstract fun initialState(): T

    interface State {
        var viewAction: ViewAction?
    }

    interface ViewAction

    data class ShowToast(val message: String) : ViewAction
    data class ConfirmTx(val tx: SafeRepository.SafeTx) : ViewAction

    @Suppress("LeakingThis")
    protected val stateChannel = ConflatedBroadcastChannel(initialState())

    protected val coroutineErrorHandler by lazy {
        CoroutineExceptionHandler { _, e ->
            e.printStackTrace()
            viewModelScope.launch { updateState(true) { viewAction = ShowToast(e.message ?: "An error occurred"); this } }
        }
    }

    protected fun currentState(): T = stateChannel.value

    protected suspend fun updateState(forceViewAction: Boolean = false, update: T.() -> T) {
        try {
            val currentState = currentState()
            val nextState = currentState.run(update)
            // Reset view action if the same
            if (!forceViewAction && nextState.viewAction === currentState.viewAction) nextState.viewAction = null
            stateChannel.send(nextState)
        } catch (e: Exception) {
            // Could not submit update
            e.printStackTrace()
            Log.e("#####", "error: $e")
        }
    }

    protected fun safeLaunch(errorHandler: CoroutineExceptionHandler = coroutineErrorHandler, block: suspend CoroutineScope.() -> Unit) =
        viewModelScope.launch(Dispatchers.IO + errorHandler, block = block)
}