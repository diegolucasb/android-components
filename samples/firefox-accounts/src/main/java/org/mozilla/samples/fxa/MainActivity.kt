/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.samples.fxa

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.customtabs.CustomTabsIntent
import android.view.View
import android.content.Intent
import android.widget.CheckBox
import android.widget.TextView
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.launch
import mozilla.components.service.fxa.FirefoxAccount
import mozilla.components.service.fxa.FxaException
import mozilla.components.service.fxa.Config
import mozilla.components.service.fxa.Profile

open class MainActivity : AppCompatActivity(), LoginFragment.OnLoginCompleteListener {

    private lateinit var whenAccount: Deferred<FirefoxAccount>
    private var scopesWithoutKeys: Array<String> = arrayOf("profile")
    private var scopesWithKeys: Array<String> = arrayOf("profile", "https://identity.mozilla.com/apps/oldsync")
    private var scopes: Array<String> = scopesWithoutKeys
    private var wantsKeys: Boolean = false

    companion object {
        const val CLIENT_ID = "12cc4070a481bc73"
        const val REDIRECT_URL = "fxaclient://android.redirect"
        const val CONFIG_URL = "https://latest.dev.lcip.org"
        const val CONFIG_URL_PAIRING = "https://pairsona.dev.lcip.org"
        const val FXA_STATE_PREFS_KEY = "fxaAppState"
        const val FXA_STATE_KEY = "fxaState"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        whenAccount = async {
            val acct = getAuthenticatedAccount()
            if (acct != null) {
                val profile = acct.getProfile(true).await()
                launch(UI) {
                    displayProfile(profile)
                }
                acct
            } else {
                val pairingUrl = intent.extras?.getString("pairingUrl")
                if (pairingUrl != null) {
                    Config.custom(CONFIG_URL_PAIRING).await().use { config ->
                        val acct = FirefoxAccount(config, CLIENT_ID, REDIRECT_URL)
                        val url = acct.beginPairingFlow(pairingUrl, scopes).await()
                        launch(UI) {
                            openWebView(url)
                        }
                        acct
                    }
                } else {
                    Config.custom(CONFIG_URL).await().use { config ->
                        FirefoxAccount(config, CLIENT_ID, REDIRECT_URL)
                    }
                }
            }
        }

        findViewById<View>(R.id.buttonCustomTabs).setOnClickListener {
            launch {
                val url = whenAccount.await().beginOAuthFlow(scopes, wantsKeys).await()
                launch(UI) { openTab(url) }
            }
        }

        findViewById<View>(R.id.buttonWebView).setOnClickListener {
            launch {
                val url = whenAccount.await().beginOAuthFlow(scopes, wantsKeys).await()
                launch(UI) { openWebView(url) }
            }
        }

        findViewById<View>(R.id.buttonPair).setOnClickListener {
            val intent = Intent(this@MainActivity, ScanActivity::class.java)
            startActivity(intent)
        }

        findViewById<View>(R.id.buttonLogout).setOnClickListener {
            getSharedPreferences(FXA_STATE_PREFS_KEY, Context.MODE_PRIVATE).edit().putString(FXA_STATE_KEY, "").apply()
            val txtView: TextView = findViewById(R.id.txtView)
            txtView.text = getString(R.string.logged_out)
        }

        findViewById<CheckBox>(R.id.checkboxKeys).setOnCheckedChangeListener { _, isChecked ->
            wantsKeys = isChecked
            scopes = if (isChecked) scopesWithKeys else scopesWithoutKeys
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::whenAccount.isInitialized) {
            launch { whenAccount.await().close() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val action = intent.action
        val data = intent.dataString

        if (Intent.ACTION_VIEW == action && data != null) {
            val url = Uri.parse(data)
            val code = url.getQueryParameter("code")
            val state = url.getQueryParameter("state")
            displayAndPersistProfile(code, state)
        }
    }

    override fun onLoginComplete(code: String, state: String, fragment: LoginFragment) {
        displayAndPersistProfile(code, state)
        supportFragmentManager?.popBackStack()
    }

    private suspend fun getAuthenticatedAccount(): FirefoxAccount? {
        val savedJSON = getSharedPreferences(FXA_STATE_PREFS_KEY, Context.MODE_PRIVATE).getString(FXA_STATE_KEY, "")
        return savedJSON?.let {
            try {
                FirefoxAccount.fromJSONString(it).await()
            } catch (e: FxaException) {
                null
            }
        } ?: null
    }

    private fun openTab(url: String) {
        val customTabsIntent = CustomTabsIntent.Builder()
                .addDefaultShareMenuItem()
                .setShowTitle(true)
                .build()

        customTabsIntent.intent.data = Uri.parse(url)
        customTabsIntent.launchUrl(this@MainActivity, Uri.parse(url))
    }

    private fun openWebView(url: String) {
        supportFragmentManager?.beginTransaction()?.apply {
            replace(R.id.container, LoginFragment.create(url, REDIRECT_URL))
            addToBackStack(null)
            commit()
        }
    }

    private fun displayAndPersistProfile(code: String, state: String) {
        launch {
            val account = whenAccount.await()
            account.completeOAuthFlow(code, state).await()
            val profile = account.getProfile().await()
            runOnUiThread {
                displayProfile(profile)
            }
            account.toJSONString().let {
                getSharedPreferences(FXA_STATE_PREFS_KEY, Context.MODE_PRIVATE)
                        .edit().putString(FXA_STATE_KEY, it).apply()
            }
        }
    }

    private fun displayProfile(profile: Profile) {
        val txtView: TextView = findViewById(R.id.txtView)
        txtView.text = getString(R.string.signed_in, "${profile.displayName ?: ""} ${profile.email}")
    }
}
