package com.ayush.apnafiles.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ayush.apnafiles.ApnaFilesApp
import com.ayush.apnafiles.ui.screens.BrowserScreen
import com.ayush.apnafiles.ui.screens.HomeScreen
import com.ayush.apnafiles.ui.screens.PairScreen
import com.ayush.apnafiles.ui.screens.SettingsScreen
import kotlinx.coroutines.launch

@Composable
fun ApnaApp(vm: ApnaViewModel) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val message by vm.message.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            vm.consumeError()
        }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(navController = nav, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    vm = vm,
                    onPair = { nav.navigate("pair") },
                    onOpenPeer = { pairId -> nav.navigate("browser/$pairId") },
                    onSettings = { nav.navigate("settings") }
                )
            }
            composable("pair") {
                PairScreen(vm = vm, onBack = { nav.popBackStack() })
            }
            composable("settings") {
                SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
            }
            composable("browser/{pairId}") { entry ->
                val pairId = entry.arguments?.getString("pairId").orEmpty()
                val app = LocalContext.current.applicationContext as ApnaFilesApp
                val browserVm: BrowserViewModel = viewModel(
                    key = "browser-$pairId",
                    factory = BrowserViewModel.factory(app, pairId)
                )
                BrowserScreen(
                    vm = browserVm,
                    onBack = { nav.popBackStack() },
                    onNotify = { text -> scope.launch { snackbar.showSnackbar(text) } }
                )
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}
