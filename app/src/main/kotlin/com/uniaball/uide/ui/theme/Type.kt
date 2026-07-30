package com.uniaball.uide.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.uniaball.uide.R

/**
 * Monospace font for the code editor. Bundled JetBrains Mono (res/font/jetbrains_mono.ttf).
 * If you ever need to drop the bundled file, switch this to [FontFamily.Monospace].
 */
val EditorFontFamily: FontFamily = FontFamily(Font(R.font.jetbrains_mono))
