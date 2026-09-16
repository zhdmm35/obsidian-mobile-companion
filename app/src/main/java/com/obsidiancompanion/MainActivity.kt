package com.obsidiancompanion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.obsidiancompanion.core.design.AppTheme
import com.obsidiancompanion.core.navigation.AppNavGraph

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 浅色主题：状态栏透明 + 深色系统图标
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                AppNavGraph()
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
