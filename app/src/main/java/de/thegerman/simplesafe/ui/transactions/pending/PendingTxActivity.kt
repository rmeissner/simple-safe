package de.thegerman.simplesafe.ui.transactions.pending

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import de.thegerman.simplesafe.R
import de.thegerman.simplesafe.repositories.SafeRepository
import de.thegerman.simplesafe.ui.base.BaseActivity
import de.thegerman.simplesafe.ui.base.BaseViewModel
import de.thegerman.simplesafe.ui.base.LoadingViewModel
import de.thegerman.simplesafe.ui.transactions.confirmation.TransactionConfirmationDialog
import de.thegerman.simplesafe.utils.asMiddleEllipsized
import kotlinx.android.synthetic.main.item_pending_tx.view.*
import kotlinx.android.synthetic.main.screen_pending_txs.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

@ExperimentalCoroutinesApi
abstract class PendingTxContract : LoadingViewModel<PendingTxContract.State>() {
    abstract fun loadTransactions()

    data class State(val loading: Boolean, val transactions: List<SafeRepository.PendingSafeTx>, override var viewAction: ViewAction?) :
        BaseViewModel.State
}

@ExperimentalCoroutinesApi
class PendingTxViewModel(
    private val safeRepository: SafeRepository
): PendingTxContract() {

    override val state = liveData {
        loadTransactions()
        for (event in stateChannel.openSubscription()) emit(event)
    }

    override fun loadTransactions() {
        if (currentState().loading) return
        loadingLaunch {
            updateState { copy(loading = true) }
            val txs = safeRepository.loadPendingTransactions()
            updateState { copy(loading = false, transactions = txs) }
        }
    }

    override fun onLoadingError(state: State, e: Throwable) = state.copy(loading = false)

    override fun initialState() = State(false, emptyList(), null)

}

@ExperimentalCoroutinesApi
class PendingTxActivity : BaseActivity<PendingTxContract.State, PendingTxContract>() {
    override val viewModel: PendingTxContract by viewModel()

    private val adapter = TransactionAdapter()
    private val layoutManager = LinearLayoutManager(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.screen_pending_txs)
        pending_txs_list.adapter = adapter
        pending_txs_list.layoutManager = layoutManager
        pending_txs_back_btn.setOnClickListener { onBackPressed() }
        pending_txs_refresh.setOnRefreshListener {
            viewModel.loadTransactions()
        }
    }

    override fun updateState(state: PendingTxContract.State) {
        pending_txs_refresh.isRefreshing = state.loading
        adapter.submitList(state.transactions)
    }

    inner class TransactionAdapter : ListAdapter<SafeRepository.PendingSafeTx, ViewHolder>(DiffCallback()) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_pending_tx, parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind(item: SafeRepository.PendingSafeTx) {
            itemView.setOnClickListener {
                TransactionConfirmationDialog(this@PendingTxActivity, item.tx, item.execInfo, item.confirmations).show()
            }
            itemView.pending_tx_target.setAddress(item.tx.to)
            itemView.pending_tx_confirmations.text = item.confirmations.size.toString()
            itemView.pending_tx_description.text = item.hash.asMiddleEllipsized(6)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<SafeRepository.PendingSafeTx>() {
        override fun areItemsTheSame(oldItem: SafeRepository.PendingSafeTx, newItem: SafeRepository.PendingSafeTx) =
            oldItem.hash == newItem.hash

        override fun areContentsTheSame(oldItem: SafeRepository.PendingSafeTx, newItem: SafeRepository.PendingSafeTx) =
            oldItem == newItem

    }

    companion object {
        fun createIntent(context: Context) = Intent(context, PendingTxActivity::class.java)
    }
}