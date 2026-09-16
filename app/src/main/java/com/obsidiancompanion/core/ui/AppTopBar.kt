package com.obsidiancompanion.core.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.obsidiancompanion.core.design.AppIcons
import com.obsidiancompanion.core.design.AppTypography

/** 带返回键的页面顶栏（原型 .appbar，高 56dp） */
@Composable
fun BackTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = 6.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            AppIconButton(icon = AppIcons.Back, contentDescription = "返回", onClick = onBack)
        }
        Text(
            text = title,
            style = AppTypography.appBarTitle,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        actions()
    }
}
