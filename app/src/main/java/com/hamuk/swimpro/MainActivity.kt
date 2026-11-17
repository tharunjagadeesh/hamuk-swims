package com.hamuk.swimpro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hamuk.swimpro.ui.SwimProRoot
import com.hamuk.swimpro.ui.SwimProTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Opened from Health Connect's "privacy policy" link.
        val showPrivacy = intent?.action == "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" ||
            intent?.action == "android.intent.action.VIEW_PERMISSION_USAGE"
        setContent {
            SwimProTheme {
                SwimProRoot(showPrivacy = showPrivacy)
            }
        }
    }
}
