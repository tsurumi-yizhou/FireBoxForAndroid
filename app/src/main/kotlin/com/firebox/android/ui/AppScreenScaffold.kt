package com.firebox.android.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.material3.rememberTopAppBarState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreenScaffold(
    title: String,
    navigationIcon: @Composable (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    navigationIcon?.invoke()
                },
                scrollBehavior = scrollBehavior,
            )
        },
        content = content,
    )
}
