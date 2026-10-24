package com.ayush.apnafiles

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ayush.apnafiles.ui.ApnaApp
import com.ayush.apnafiles.ui.ApnaViewModel
import com.ayush.apnafiles.ui.theme.ApnaFilesTheme

class MainActivity : ComponentActivity() {

    private val vm: ApnaViewModel by viewModels { ApnaViewModel.factory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val app = application as ApnaFilesApp
        setContent {
            val dynamicColor by app.prefs.dynamicColor.collectAsStateWithLifecycle()
            ApnaFilesTheme(dynamicColor = dynamicColor) {
                ApnaApp(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
        vm.onBackground()
        super.onStop()
    }
}
