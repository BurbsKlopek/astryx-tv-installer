package com.astryxtv.installer.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

enum class InstallerThemeId(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val palette: InstallerPalette
) {
    TOKYO_CYBER(
        id = "tokyo_cyber",
        displayName = "Tokyo Cyber",
        subtitle = "Pitch neon night with hot magenta and electric cyan",
        palette = InstallerPalette(
            canvasTop = Color(0xFF090612),
            canvasBottom = Color(0xFF120A24),
            ambientPrimary = Color(0xFFFF2E93),
            ambientSecondary = Color(0xFF00F0FF),
            glassSurface = Color(0xC8180E2C),
            glassElevated = Color(0xDF21133B),
            glassBorder = Color(0x40FF2E93),
            accentStart = Color(0xFFFF2E93),
            accentEnd = Color(0xFF00F0FF),
            inkPrimary = Color(0xFFFFFFFF),
            inkSecondary = Color(0xFFC0B3D9),
            success = Color(0xFF00F0FF),
            warning = Color(0xFFFBBF24),
            danger = Color(0xFFFF4D6D)
        )
    ),
    ZENITH_SAPPHIRE(
        id = "zenith_sapphire",
        displayName = "Zenith Sapphire",
        subtitle = "Deep horizon sky with cyan and indigo glow",
        palette = InstallerPalette(
            canvasTop = Color(0xFF060B18),
            canvasBottom = Color(0xFF0B1226),
            ambientPrimary = Color(0xFF38BDF8),
            ambientSecondary = Color(0xFF6366F1),
            glassSurface = Color(0xBD101B33),
            glassElevated = Color(0xD9162444),
            glassBorder = Color(0x2EFFFFFF),
            accentStart = Color(0xFF38BDF8),
            accentEnd = Color(0xFF6366F1),
            inkPrimary = Color(0xFFF8FAFC),
            inkSecondary = Color(0xFF94A3B8),
            success = Color(0xFF34D399),
            warning = Color(0xFFFBBF24),
            danger = Color(0xFFFB7185)
        )
    ),
    HYPER_VIOLET(
        id = "hyper_violet",
        displayName = "Hyper Violet",
        subtitle = "Midnight plum lit by electric violet and magenta",
        palette = InstallerPalette(
            canvasTop = Color(0xFF0B0618),
            canvasBottom = Color(0xFF170B2E),
            ambientPrimary = Color(0xFFA855F7),
            ambientSecondary = Color(0xFFEC4899),
            glassSurface = Color(0xC21D1038),
            glassElevated = Color(0xDC27154A),
            glassBorder = Color(0x33E9D5FF),
            accentStart = Color(0xFFA855F7),
            accentEnd = Color(0xFFEC4899),
            inkPrimary = Color(0xFFFAF5FF),
            inkSecondary = Color(0xFFC4B5FD),
            success = Color(0xFF34D399),
            warning = Color(0xFFFBBF24),
            danger = Color(0xFFFB7185)
        )
    ),
    ARCTIC_FROST(
        id = "arctic_frost",
        displayName = "Arctic Frost",
        subtitle = "Glacial blue-slate with icy cyan liquid glass",
        palette = InstallerPalette(
            canvasTop = Color(0xFF06121A),
            canvasBottom = Color(0xFF0B202E),
            ambientPrimary = Color(0xFF67E8F9),
            ambientSecondary = Color(0xFF0EA5E9),
            glassSurface = Color(0xC20E2636),
            glassElevated = Color(0xDC133348),
            glassBorder = Color(0x38BAE6FD),
            accentStart = Color(0xFF67E8F9),
            accentEnd = Color(0xFF0EA5E9),
            inkPrimary = Color(0xFFF0F9FF),
            inkSecondary = Color(0xFF93C5DF),
            success = Color(0xFF34D399),
            warning = Color(0xFFFBBF24),
            danger = Color(0xFFFB7185)
        )
    ),
    SOLAR_AMBER(
        id = "solar_amber",
        displayName = "Solar Amber",
        subtitle = "Obsidian dusk warmed by golden flare",
        palette = InstallerPalette(
            canvasTop = Color(0xFF0F0905),
            canvasBottom = Color(0xFF1A1008),
            ambientPrimary = Color(0xFFFBBF24),
            ambientSecondary = Color(0xFFF97316),
            glassSurface = Color(0xBD22150B),
            glassElevated = Color(0xD92D1C0E),
            glassBorder = Color(0x2EFFD7A8),
            accentStart = Color(0xFFFBBF24),
            accentEnd = Color(0xFFEA580C),
            inkPrimary = Color(0xFFFFFBEB),
            inkSecondary = Color(0xFFD6B99B),
            success = Color(0xFF34D399),
            warning = Color(0xFFFDE047),
            danger = Color(0xFFFB7185)
        )
    ),
    EMERALD_PULSE(
        id = "emerald_pulse",
        displayName = "Emerald Pulse",
        subtitle = "Northern aurora jade and ocean cyan",
        palette = InstallerPalette(
            canvasTop = Color(0xFF04120E),
            canvasBottom = Color(0xFF071E1B),
            ambientPrimary = Color(0xFF34D399),
            ambientSecondary = Color(0xFF06B6D4),
            glassSurface = Color(0xBD0C241E),
            glassElevated = Color(0xD9113029),
            glassBorder = Color(0x2EA7F3D0),
            accentStart = Color(0xFF10B981),
            accentEnd = Color(0xFF06B6D4),
            inkPrimary = Color(0xFFECFDF5),
            inkSecondary = Color(0xFF94C5B8),
            success = Color(0xFF4ADE80),
            warning = Color(0xFFFBBF24),
            danger = Color(0xFFFB7185)
        )
    ),
    CRIMSON_DUSK(
        id = "crimson_dusk",
        displayName = "Crimson Dusk",
        subtitle = "Velvet twilight with rose and ultraviolet",
        palette = InstallerPalette(
            canvasTop = Color(0xFF12060E),
            canvasBottom = Color(0xFF1A0B24),
            ambientPrimary = Color(0xFFF43F5E),
            ambientSecondary = Color(0xFFA855F7),
            glassSurface = Color(0xBD241022),
            glassElevated = Color(0xD930162E),
            glassBorder = Color(0x2EFECDD3),
            accentStart = Color(0xFFF43F5E),
            accentEnd = Color(0xFFA855F7),
            inkPrimary = Color(0xFFFFF1F2),
            inkSecondary = Color(0xFFC9A3B8),
            success = Color(0xFF34D399),
            warning = Color(0xFFFBBF24),
            danger = Color(0xFFFDA4AF)
        )
    ),
    BLOOD_MOON(
        id = "blood_moon",
        displayName = "Blood Moon",
        subtitle = "Smoked ember charcoal with ruby-to-amber flare",
        palette = InstallerPalette(
            canvasTop = Color(0xFF110607),
            canvasBottom = Color(0xFF210A0C),
            ambientPrimary = Color(0xFFEF4444),
            ambientSecondary = Color(0xFFF59E0B),
            glassSurface = Color(0xC42A0E11),
            glassElevated = Color(0xDE381317),
            glassBorder = Color(0x33FECACA),
            accentStart = Color(0xFFEF4444),
            accentEnd = Color(0xFFF59E0B),
            inkPrimary = Color(0xFFFEF2F2),
            inkSecondary = Color(0xFFD9A6A6),
            success = Color(0xFF4ADE80),
            warning = Color(0xFFFBBF24),
            danger = Color(0xFFFCA5A5)
        )
    ),
    MINT_AURORA(
        id = "mint_aurora",
        displayName = "Mint Aurora",
        subtitle = "Deep forest teal lit by mint and electric lime",
        palette = InstallerPalette(
            canvasTop = Color(0xFF051314),
            canvasBottom = Color(0xFF09221C),
            ambientPrimary = Color(0xFF2DD4BF),
            ambientSecondary = Color(0xFFA3E635),
            glassSurface = Color(0xC20D2623),
            glassElevated = Color(0xDC12332E),
            glassBorder = Color(0x36A7F3D0),
            accentStart = Color(0xFF2DD4BF),
            accentEnd = Color(0xFFA3E635),
            inkPrimary = Color(0xFFF0FDFA),
            inkSecondary = Color(0xFF99CFC6),
            success = Color(0xFFA3E635),
            warning = Color(0xFFFBBF24),
            danger = Color(0xFFFB7185)
        )
    ),
    OBSIDIAN_CHROME(
        id = "obsidian_chrome",
        displayName = "Obsidian Chrome",
        subtitle = "Stealth smoked glass with bright platinum edge",
        palette = InstallerPalette(
            canvasTop = Color(0xFF080A0F),
            canvasBottom = Color(0xFF11151F),
            ambientPrimary = Color(0xFFE2E8F0),
            ambientSecondary = Color(0xFF64748B),
            glassSurface = Color(0xC6181D28),
            glassElevated = Color(0xE0202736),
            glassBorder = Color(0x3BE2E8F0),
            accentStart = Color(0xFFF1F5F9),
            accentEnd = Color(0xFF94A3B8),
            inkPrimary = Color(0xFFF8FAFC),
            inkSecondary = Color(0xFFA0AEC0),
            success = Color(0xFF34D399),
            warning = Color(0xFFFBBF24),
            danger = Color(0xFFFB7185)
        )
    );

    companion object {
        fun fromId(id: String?): InstallerThemeId =
            entries.firstOrNull { it.id == id } ?: TOKYO_CYBER
    }
}

enum class AccentOverrideId(
    val id: String,
    val label: String,
    val start: Color?,
    val end: Color?
) {
    THEME_DEFAULT("default", "Auto", null, null),
    NEON_CYBER("neon_cyber", "Cyber", Color(0xFFFF2E93), Color(0xFF00F0FF)),
    ELECTRIC_CYAN("electric_cyan", "Cyan", Color(0xFF38BDF8), Color(0xFF0EA5E9)),
    VIOLET_PULSE("violet_pulse", "Violet", Color(0xFFA855F7), Color(0xFFEC4899)),
    SOLAR_GOLD("solar_gold", "Gold", Color(0xFFFBBF24), Color(0xFFEA580C)),
    JADE_GLOW("jade_glow", "Jade", Color(0xFF10B981), Color(0xFF2DD4BF)),
    RUBY_FLARE("ruby_flare", "Ruby", Color(0xFFEF4444), Color(0xFFF43F5E)),
    PLATINUM("platinum", "Chrome", Color(0xFFF8FAFC), Color(0xFF94A3B8));

    companion object {
        fun fromId(id: String?): AccentOverrideId =
            entries.firstOrNull { it.id == id } ?: THEME_DEFAULT
    }
}

@Immutable
data class InstallerPalette(
    val canvasTop: Color,
    val canvasBottom: Color,
    val ambientPrimary: Color,
    val ambientSecondary: Color,
    val glassSurface: Color,
    val glassElevated: Color,
    val glassBorder: Color,
    val accentStart: Color,
    val accentEnd: Color,
    val inkPrimary: Color,
    val inkSecondary: Color,
    val success: Color,
    val warning: Color,
    val danger: Color
)

val LocalInstallerPalette = staticCompositionLocalOf { InstallerThemeId.TOKYO_CYBER.palette }

private val InstallerTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.6).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.3).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 18.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)

@Composable
fun AstryxInstallerTheme(
    themeId: InstallerThemeId,
    accentOverride: AccentOverrideId = AccentOverrideId.THEME_DEFAULT,
    content: @Composable () -> Unit
) {
    val base = themeId.palette
    val target = if (accentOverride.start != null && accentOverride.end != null) {
        base.copy(
            ambientPrimary = accentOverride.start,
            ambientSecondary = accentOverride.end,
            accentStart = accentOverride.start,
            accentEnd = accentOverride.end
        )
    } else {
        base
    }

    val animSpec = tween<Color>(durationMillis = 320)

    val canvasTop by animateColorAsState(target.canvasTop, animSpec, label = "canvasTop")
    val canvasBottom by animateColorAsState(target.canvasBottom, animSpec, label = "canvasBottom")
    val ambientPrimary by animateColorAsState(target.ambientPrimary, animSpec, label = "ambientPrimary")
    val ambientSecondary by animateColorAsState(target.ambientSecondary, animSpec, label = "ambientSecondary")
    val glassSurface by animateColorAsState(target.glassSurface, animSpec, label = "glassSurface")
    val glassElevated by animateColorAsState(target.glassElevated, animSpec, label = "glassElevated")
    val glassBorder by animateColorAsState(target.glassBorder, animSpec, label = "glassBorder")
    val accentStart by animateColorAsState(target.accentStart, animSpec, label = "accentStart")
    val accentEnd by animateColorAsState(target.accentEnd, animSpec, label = "accentEnd")
    val inkPrimary by animateColorAsState(target.inkPrimary, animSpec, label = "inkPrimary")
    val inkSecondary by animateColorAsState(target.inkSecondary, animSpec, label = "inkSecondary")

    val animatedPalette = InstallerPalette(
        canvasTop = canvasTop,
        canvasBottom = canvasBottom,
        ambientPrimary = ambientPrimary,
        ambientSecondary = ambientSecondary,
        glassSurface = glassSurface,
        glassElevated = glassElevated,
        glassBorder = glassBorder,
        accentStart = accentStart,
        accentEnd = accentEnd,
        inkPrimary = inkPrimary,
        inkSecondary = inkSecondary,
        success = target.success,
        warning = target.warning,
        danger = target.danger
    )

    val m3Scheme = darkColorScheme(
        primary = animatedPalette.accentStart,
        secondary = animatedPalette.accentEnd,
        background = animatedPalette.canvasTop,
        surface = animatedPalette.glassSurface,
        onPrimary = Color(0xFF040812),
        onBackground = animatedPalette.inkPrimary,
        onSurface = animatedPalette.inkPrimary,
        error = animatedPalette.danger
    )

    CompositionLocalProvider(LocalInstallerPalette provides animatedPalette) {
        MaterialTheme(
            colorScheme = m3Scheme,
            typography = InstallerTypography,
            content = content
        )
    }
}
