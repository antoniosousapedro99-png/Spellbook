package com.cybernomicon.grimoire

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cybernomicon.grimoire.ui.AppViewModel
import com.cybernomicon.grimoire.ui.GrimoireApp
import com.cybernomicon.grimoire.ui.theme.CybernomiconTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CybernomiconTheme {
                val vm: AppViewModel = viewModel()
                GrimoireApp(vm)
            }
        }
    }
}
