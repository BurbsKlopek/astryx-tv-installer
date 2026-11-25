package com.astryxtv.installer

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.astryxtv.installer.ui.AstryxInstallerTheme
import com.astryxtv.installer.ui.InstallerScreen
import com.astryxtv.installer.ui.InstallerViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: InstallerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        setContent {
            val ui by viewModel.ui.collectAsState()
            AstryxInstallerTheme(
                themeId = ui.themeId,
                accentOverride = ui.accentOverride
            ) {
                InstallerScreen(viewModel = viewModel)
            }
        }
    }
}
