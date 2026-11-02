package com.example.cpen321application.helpers

import com.example.cpen321application.*
import android.content.Context
import androidx.compose.runtime.*
import androidx.fragment.app.FragmentActivity

/** Direct screen fixtures use their own disabled lock, without touching device settings. */
@Composable
internal fun TestAppLockProvider(activity: FragmentActivity, content: @Composable () -> Unit) {
    val controller = remember(activity) { AppLockController(activity,
        activity.getSharedPreferences("screen-fixture-disabled-lock", Context.MODE_PRIVATE)) }
    CompositionLocalProvider(LocalAppLock provides controller, content = content)
}
