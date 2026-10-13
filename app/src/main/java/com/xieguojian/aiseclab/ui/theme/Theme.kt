package com.xieguojian.aiseclab.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF3C3489),
    secondary = Color(0xFF1D9E75),
    tertiary = Color(0xFFD85A30)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAFA9EC),
    secondary = Color(0xFF5DCAA5),
    tertiary = Color(0xFFF0997B)
)

@Composable
fun AISecLabTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // 语义色（好 / 拿不准 / 偏高 / 糟 / 无数据）随主题切明暗两档，见 SemanticColors.kt。
    // 用 CompositionLocal 而不是把这些色塞进 MaterialTheme 的 colorScheme：M3 的槽位
    // （tertiary / error…）代表**控件角色**，不是"结论好坏"，硬塞进去下一个读代码的人
    // 只能靠猜 —— 而「哪个等级对应哪个语义」这件事必须能一眼看懂。
    CompositionLocalProvider(
        LocalSemanticColors provides semanticColorsFor(darkTheme)
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            content = content
        )
    }
}
