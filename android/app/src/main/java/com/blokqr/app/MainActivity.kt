package com.blokqr.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.blokqr.app.ui.AppNavigation
import com.blokqr.app.ui.theme.BlokQrTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BlokQrTheme {
                Surface(Modifier.fillMaxSize()) {
                    AppNavigation()
                }
            }
        }
    }
}
