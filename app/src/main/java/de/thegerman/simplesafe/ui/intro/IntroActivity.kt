package de.thegerman.simplesafe.ui.intro

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
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
import kotlinx.android.synthetic.main.screen_intro_slider_create.view.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.koin.android.viewmodel.ext.android.viewModel
import pm.gnosis.svalinn.common.utils.getColorCompat
import java.lang.Exception


@ExperimentalCoroutinesApi
class IntroActivity : BaseActivity<IntroViewModelContract.State, IntroViewModelContract>() {

    private val credentialsOptions = CredentialsOptions.Builder().forceEnableSaveDialog().build()
    private lateinit var credentialsClient: CredentialsClient

    override val viewModel: IntroViewModelContract by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        credentialsClient = Credentials.getClient(this, credentialsOptions)
        setContentView(R.layout.screen_intro)

        intro_view_pager.adapter = viewPager
        intro_view_pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                super.onPageScrolled(position, positionOffset, positionOffsetPixels)
                if (layouts.getOrNull(position + 1) == R.layout.screen_intro_slider_create) {
                    val bgColor = ColorUtils.blendARGB(getColorCompat(R.color.transparent), getColorCompat(R.color.colorPrimary), positionOffset)
                    intro_root.setBackgroundColor(bgColor)
                    val indicatorColor =
                        ColorUtils.blendARGB(getColorCompat(R.color.colorPrimary), getColorCompat(R.color.white), positionOffset)
                    intro_pager_indicator.setActiveColor(indicatorColor)
                } else if (layouts.getOrNull(position) == R.layout.screen_intro_slider_create) {
                    intro_root.setBackgroundColor(getColorCompat(R.color.colorPrimary))
                    intro_pager_indicator.setActiveColor(getColorCompat(R.color.white))
                } else {
                    intro_root.background = null
                    intro_pager_indicator.setActiveColor(getColorCompat(R.color.colorPrimary))
                }
                println("$position, $positionOffset, $positionOffsetPixels")
            }
        })
        intro_pager_indicator.setViewPager(intro_view_pager)
    }

    override fun updateState(state: IntroViewModelContract.State) {
        viewPager.state = state
        if (state.setup) {
            startActivity(MainActivity.createIntent(this))
            finish()
        } else {
            intro_view_pager.isVisible = true
            intro_pager_indicator.isVisible = true
        }
        when (val error = state.recoverError?.error) {
            is ResolvableApiException -> {
                try {
                    error.startResolutionForResult(this, state.recoverError.requestCode())
                } catch (e: IntentSender.SendIntentException) {
                    e.printStackTrace()
                    viewModel.handleCredentialsStored()
                }
            }
            is Exception -> {
                error.printStackTrace()
                viewModel.handleCredentialsStored()
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
                Toast.makeText(this, "Could not restore account!", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == RC_WRITE) {
            viewModel.handleCredentialsStored()
        }
    }

    private val layouts =
        listOf(
            R.layout.screen_intro_slider_first,
            R.layout.screen_intro_slider_second,
            R.layout.screen_intro_slider_third,
            R.layout.screen_intro_slider_fourth,
            R.layout.screen_intro_slider_create
        )

    private val viewPager = object : PagerAdapter() {

        var state: IntroViewModelContract.State? = null
            set(value) {
                field = value!!
                updateCreateView()
            }

        var createView: View? = null
        private fun onInflatedView(id: Int, view: View) {
            if (id == R.layout.screen_intro_slider_create) {
                createView = view
                updateCreateView()
                view.intro_new_account_btn.setOnClickListener {
                    viewModel.initNewAccount(credentialsClient)
                }

                view.intro_recover_btn.setOnClickListener {
                    viewModel.credentialsRequest(credentialsClient)
                }
            }
        }

        private fun onClearView(id: Int, view: View) {
            if (id == R.layout.screen_intro_slider_create) {
                createView = null
            }
        }

        private fun updateCreateView() {
            val createView = createView ?: return
            val state = state ?: return
            createView.intro_progress.isVisible = state.loading
            createView.intro_new_account_btn.isVisible = !state.setup && !state.loading
            createView.intro_recover_btn.isVisible = !state.setup && !state.loading
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val id = layouts[position]
            val layout = LayoutInflater.from(container.context).inflate(id, container, false) as ViewGroup
            onInflatedView(id, layout)
            container.addView(layout)
            return layout
        }

        override fun destroyItem(container: ViewGroup, position: Int, any: Any) {
            (any as? View)?.let {
                container.removeView(it)
                onClearView(layouts[position], it)
            }
        }

        override fun isViewFromObject(view: View, any: Any) = view == any

        override fun getCount(): Int = layouts.size
    }


    companion object {
        private const val RC_READ = 12343
        private const val RC_WRITE = 12365
    }

}