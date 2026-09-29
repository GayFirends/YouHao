package com.youhao.fueltrack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.youhao.fueltrack.ui.YouHaoApp
import com.youhao.fueltrack.ui.theme.YouHaoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            YouHaoTheme {
                YouHaoApp()
            }
        }
    }
}
