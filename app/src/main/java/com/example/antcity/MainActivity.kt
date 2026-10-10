package com.example.antcity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import com.example.antcity.ui.AntCityApp
import com.example.antcity.ui.AntCityTheme
import com.example.antcity.ui.LocalSprites
import com.example.antcity.ui.Sprites

class MainActivity : ComponentActivity() {
    private val vm: GameViewModel by viewModels()
    private val sprites by lazy { Sprites(assets) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalSprites provides sprites) {
                AntCityTheme {
                    AntCityApp(vm)
                }
            }
        }
    }
}
