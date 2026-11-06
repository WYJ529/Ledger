package com.example.cpen321application

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import com.example.cpen321application.ui.theme.CPEN321ApplicationTheme

class MainActivity : ComponentActivity() {
    private var invitationLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        L10n.load(this)
        if (intent?.action == Intent.ACTION_VIEW) invitationLink = intent?.dataString
        val ledger = ViewModelProvider(this)[LedgerViewModel::class.java]
        setContent { CPEN321ApplicationTheme { LedgerApp(ledger, invitationLink, onInvitationHandled = {
            invitationLink = null
            intent?.let { setIntent(Intent(it).setData(null)) }
        }) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) invitationLink = intent.dataString
    }
}
