package com.mynok.myagent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.StateFlow

/**
 * 收集 StateFlow 的 Compose 状态。
 *
 * 之所以包一层而不是直接用 lifecycle 的版本：这里的 ViewModel 都是
 * viewModel(factory=...) 创建的，lifecycle-runtime-compose 的版本需要
 * 正确的 LocalLifecycleOwner，在 Compose 1.7+ 上行为一致。
 */
@Composable
fun <T> StateFlow<T>.collectAsStateWithLifecycleCompat(): State<T> = collectAsState()
