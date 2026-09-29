package com.youhao.fueltrack.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 「松间仪表盘」调色板 —— 逐值取自原 Vue 版 `src/assets/main.css` 的 2026 视觉刷新块。
 *
 * CSS 变量与这里的命名一一对应，方便对着设计源核对：
 * - `--bg / --surface / --surface-alt / --ink / --muted / --line` → `Paper*`
 * - `--accent / --accent-dark / --accent-soft / --hero` → `Pine*`
 * - `--signal`（青柠）→ `Signal`，是「仪表盘亮灯」的唯一亮点色
 * - `--danger / --danger-soft` → `Danger*`
 *
 * 深色不是浅色的反相：原设计只有浅色一版，深色沿用同一套松绿骨架、
 * 把「页面底」压到近黑、把强调色提亮（#236B50 在深底上只有 2.9:1，不能当文字色）。
 */

// ---- 浅色：纸感底 + 松绿主色 + 青柠信号 ----
val PaperBg = Color(0xFFEDF1EB) // --bg
val PaperSurface = Color(0xFFFBFCF8) // --surface
val PaperSurfaceAlt = Color(0xFFF1F5EF) // --surface-alt
val PaperInk = Color(0xFF17261F) // --ink
val PaperMuted = Color(0xFF68766E) // --muted
val PaperLine = Color(0xFFDBE3DA) // --line，装饰性分隔线
val PaperLineStrong = Color(0xFF8FA79A) // 交互描边：加深到对白卡片 3.1:1，避免「描边消失」

val PineAccent = Color(0xFF236B50) // --accent
val PineAccentDark = Color(0xFF164B38) // --accent-dark
val PineAccentSoft = Color(0xFFDCECE1) // --accent-soft
val PineHero = Color(0xFF153F34) // --hero，深绿仪表盘卡
val PineHeroDeep = Color(0xFF0D2822) // .sidebar 渐变的暗端
val PineHeroInk = Color(0xFFF8FFF9) // 深绿卡上的正文
val PineHeroMuted = Color(0xFFABC5B8) // 深绿卡上的次级文字
val PineHeroSoft = Color(0xFFB8CFC3) // .records-summary small
val Signal = Color(0xFFD8FF72) // --signal，青柠
val SignalDeep = Color(0xFFCFFF51) // .button.primary:hover
val SapInk = Color(0xFF163329) // .button.primary 的文字色

val DangerRed = Color(0xFFB8463B) // --danger
val DangerSoft = Color(0xFFF8E5E1) // --danger-soft

/** 卡片投影：原设计 `0 16px 40px rgba(24,55,43,.07)`，色相是松绿而不是中性灰。 */
val CardShadow = Color(0x3D14342A)

/**
 * 页面顶部的环境光晕：body 的
 * `radial-gradient(circle at 90% 0, rgba(164,207,170,.24), transparent 19rem)`。
 */
val AmbientGlow = Color(0x3DA4CFAA)

// ---- 深色：同一套骨架压暗，强调色提亮 ----
val NightBg = Color(0xFF0B1512)
val NightSurface = Color(0xFF13201B)
val NightSurfaceAlt = Color(0xFF182620)
val NightInk = Color(0xFFE9F1EC)
val NightMuted = Color(0xFF9DB0A7)
val NightLine = Color(0xFF25352E)
val NightLineStrong = Color(0xFF6E8C7F) // 对卡片 #13201B 为 3.2:1

val NightAccent = Color(0xFF7FC7A3) // 填充，对深底 8.2:1
val NightAccentDark = Color(0xFFA9DFC3) // 强调文字 / 数字，对 #13201B 为 9.8:1
val NightAccentSoft = Color(0xFF1C3A30)
val NightHero = Color(0xFF0F2E26)
val NightHeroMuted = Color(0xFF8FAEA1)

val NightDanger = Color(0xFFF2B8B5)
val NightDangerSoft = Color(0xFF3A1F1C)
val NightErrorContainer = Color(0xFF5C2420)
val NightOnErrorContainer = Color(0xFFF9DEDC)

val AlertRed = Color(0xFFB3261E)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF410002)
