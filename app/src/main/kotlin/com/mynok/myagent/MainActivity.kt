package com.mynok.myagent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.media.projection.MediaProjectionManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.mynok.myagent.data.repo.AppSettings
import com.mynok.myagent.tools.android.ProjectionService
import com.mynok.myagent.ui.MyAgentRoot
import com.mynok.myagent.ui.theme.MyAgentTheme

class MainActivity : ComponentActivity() {

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            ProjectionService.start(this, result.resultCode, data)
        }
    }

    private val treeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val cb = pendingTreeCallback
        pendingTreeCallback = null
        if (uri != null) cb?.invoke(listOf(uri))
    }

    private val imageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val cb = pendingImageCallback
        pendingImageCallback = null
        if (uri != null) cb?.invoke(uri)
    }

    private var pendingTreeCallback: ((List<Uri>) -> Unit)? = null
    private var pendingImageCallback: ((Uri) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as MyAgentApp).container
        setContent {
            // 外观设置直接在最外层收集，保证底部导航、对话框等也一起换肤
            val settings by container.settingsStore.settings.collectAsState(initial = AppSettings())
            MyAgentTheme(
                themeMode = settings.themeMode,
                themeColor = settings.themeColor,
                dynamicColor = settings.dynamicColor,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MyAgentRoot(container)
                }
            }
        }
    }

    /** 启动屏幕共享授权流程。 */
    fun requestScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    /** 让用户选一个目录用于文件类工具。 */
    fun requestDirectory(callback: (List<Uri>) -> Unit) {
        pendingTreeCallback = callback
        treeLauncher.launch(null)
    }

    /** 让用户选一张图片。 */
    fun requestImage(callback: (Uri) -> Unit) {
        pendingImageCallback = callback
        imageLauncher.launch("image/*")
    }
}
