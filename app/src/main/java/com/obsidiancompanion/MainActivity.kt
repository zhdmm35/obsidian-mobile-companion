package com.obsidiancompanion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.obsidiancompanion.core.design.AppTheme
import com.obsidiancompanion.core.navigation.AppNavGraph

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
            AppGraph.pendingSharedText.value = text
            intent.removeExtra(Intent.EXTRA_TEXT) // 防旋转重建时重复入队
        }
    }

    override fun onStart() {
        super.onStart()
        // Phase 6B：网络恢复监听（幂等启动）+ 回到前台刷新（3 分钟阈值 + freshness window 去重）
        AppGraph.refreshTriggers.start()
        AppGraph.refreshTriggers.onForeground()
    }
}
