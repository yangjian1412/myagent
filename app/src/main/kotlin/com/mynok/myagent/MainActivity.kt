package com.mynok.myagent

import android.net.Uri
import android.os.Bundle
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
import com.mynok.myagent.ui.MyAgentRoot
import com.mynok.myagent.ui.theme.MyAgentTheme

class MainActivity : ComponentActivity() {

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
    private var pendingFileCallback: ((Uri) -> Unit)? = null
    private var pendingSkillDirCallback: ((Uri) -> Unit)? = null
    private var pendingPhotoUri: Uri? = null

    private val fileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val cb = pendingFileCallback
        pendingFileCallback = null
        if (uri != null) cb?.invoke(uri)
    }

    /** 通知要求打开的会话 id；Compose 里读它来跳转。 */
    private val pendingConversationId = androidx.compose.runtime.mutableStateOf<String?>(null)

    private val skillDirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val cb = pendingSkillDirCallback
        pendingSkillDirCallback = null
        if (uri != null) cb?.invoke(uri)
    }

    private val photoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val container = (application as MyAgentApp).container
        container.mediaCapture.resolve(if (success) pendingPhotoUri else null)
        pendingPhotoUri = null
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_CONVERSATION_ID)?.let { pendingConversationId.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingPhotoUri = savedInstanceState?.getString(KEY_PENDING_PHOTO)?.let(Uri::parse)
        pendingConversationId.value = intent?.getStringExtra(EXTRA_CONVERSATION_ID)
        val container = (application as MyAgentApp).container
        container.mediaCapture.attach { uri ->
            pendingPhotoUri = uri
            photoLauncher.launch(uri)
        }
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
                    MyAgentRoot(
                        container = container,
                        initialConversationId = pendingConversationId.value,
                    )
                }
            }
        }
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

    /** 让用户选一个文件（PDF、代码、文档等）。 */
    fun requestFile(callback: (Uri) -> Unit) {
        pendingFileCallback = callback
        fileLauncher.launch(arrayOf("*/*"))
    }

    /** 让用户选一个目录作为技能存放位置。 */
    fun requestSkillDirectory(callback: (Uri) -> Unit) {
        pendingSkillDirCallback = callback
        skillDirLauncher.launch(null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingPhotoUri?.let { outState.putString(KEY_PENDING_PHOTO, it.toString()) }
    }

    override fun onDestroy() {
        // 转屏时不要把正在等待的拍照结果取消掉；真正退出时再清理。
        if (!isChangingConfigurations) {
            (application as? MyAgentApp)?.container?.mediaCapture?.attach(null)
        }
        super.onDestroy()
    }

    companion object {
        /** 通知里带的会话 id，点通知直接进那个会话。 */
        const val EXTRA_CONVERSATION_ID = "conversation_id"

        private const val KEY_PENDING_PHOTO = "pending_photo_uri"
    }
}
