package de.thegerman.simplesafe.ui.moon

import android.os.Bundle
import android.view.KeyEvent
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import de.thegerman.simplesafe.R
import kotlinx.android.synthetic.main.screen_buy_dai.*

// https://buy.moonpay.io/?currencyCode=dai&walletAddress=0x05c85Ab5B09Eb8A55020d72daf6091E04e264af9&redirectURL=


class BuyDaiActivity: AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.screen_buy_dai)
        buy_dai_webview.loadUrl("https://buy.moonpay.io/?currencyCode=dai&walletAddress=0x567725581c7518D86c7d163Dd579b2c4258337d0")
        buy_dai_webview.webViewClient = WebViewClient()
        buy_dai_webview.settings.javaScriptEnabled = true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Check if the key event was the Back button and if there's history
        if (keyCode == KeyEvent.KEYCODE_BACK && buy_dai_webview.canGoBack()) {
            buy_dai_webview.goBack()
            return true
        }
        // If it wasn't the Back key or there's no web page history, bubble up to the default
        // system behavior (probably exit the activity)
        return super.onKeyDown(keyCode, event)
    }
}