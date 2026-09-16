package com.obsidiancompanion.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppSpacing
import com.obsidiancompanion.core.design.AppTypography

data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

/** 底部导航（原型 .bnav：暖白底 + 顶部分隔线，4 tab，激活 fg/未激活 muted） */
@Composable
fun AppBottomNavigation(
    items: List<BottomNavItem>,
    currentRoute: String?,
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().background(AppColors.surface)) {
        AppHorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 6.dp, end = 6.dp, top = 6.dp),
        ) {
            items.forEach { item ->
                val selected = item.route == currentRoute
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onItemClick(item.route) }
                        .padding(top = 8.dp, bottom = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            item.icon,
                            contentDescription = item.label,
                            tint = if (selected) AppColors.textPrimary else AppColors.textTertiary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = item.label,
                        style = AppTypography.bottomNavLabel,
                        color = if (selected) AppColors.textPrimary else AppColors.textTertiary,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
    }
}
