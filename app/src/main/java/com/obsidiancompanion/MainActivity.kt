package com.obsidiancompanion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.obsidiancompanion.core.design.AppTheme
import com.obsidiancompanion.core.navigation.AppNavGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 状态栏透明，系统图标颜色跟随系统深色/浅色
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)
        setContent {
            AppTheme {
                AppNavGraph()
            }
        }
    }

    /** singleTask：已运行的实例经 onNewIntent 收到分享。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return
            intent.removeExtra(Intent.EXTRA_TEXT) // 防旋转重建时重复入队
            val app = applicationContext
            AppGraph.appScope.launch {
                try {
                    val draft = AppGraph.captureDraft.create(text, com.obsidiancompanion.feature.capture.defaultCaptureTitle(text))
                    AppGraph.pendingCaptureId.value = draft.id // 落盘成功后才打开收集页。
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(app, "分享内容保存失败，请重新分享", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Phase 6B：网络恢复监听（幂等启动）+ 回到前台刷新（3 分钟阈值 + freshness window 去重）
        AppGraph.refreshTriggers.start()
        AppGraph.refreshTriggers.onForeground()
    }
}
