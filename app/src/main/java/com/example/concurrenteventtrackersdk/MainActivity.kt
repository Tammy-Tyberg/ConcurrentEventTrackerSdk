package com.example.concurrenteventtrackersdk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.concurrenteventtrackersdk.ui.theme.ConcurrentEventTrackerSdkTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ConcurrentEventTrackerSdkTheme {
                TrackerDemoScreen()
            }
        }
    }
}
