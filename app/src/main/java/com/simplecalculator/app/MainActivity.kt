package com.simplecalculator.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.simplecalculator.app.core.Session
import com.simplecalculator.app.ui.LocalContainer
import com.simplecalculator.app.ui.Root

class MainActivity : AppCompatActivity() {

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied; Media3 handles either case */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applySecureFlag()
        requestNotificationPermissionIfNeeded()
        val container = (application as App).container
        Session.lock()
        setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                Root()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Blocks screenshots and the recents thumbnail when the user asked for it. */
    fun applySecureFlag() {
        val secure = getSharedPreferences("calc_state", MODE_PRIVATE)
            .getBoolean("secure_screen", false)
        if (secure) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onResume() {
        super.onResume()
        applySecureFlag()
    }

    override fun onStop() {
        super.onStop()
        Session.noteBackground()
        if (getSharedPreferences("calc_state", MODE_PRIVATE)
                .getBoolean("lock_on_background", true)
        ) {
            Session.lock()
        }
    }

    override fun onStart() {
        super.onStart()
        if (Session.shouldRelock()) Session.lock()
    }
}
