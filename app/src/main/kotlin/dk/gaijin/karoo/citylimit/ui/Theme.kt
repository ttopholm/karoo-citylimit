package dk.gaijin.karoo.citylimit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val colorScheme = lightColorScheme(
    primary = Color(0xFF1B5E20),
    onPrimary = Color.White,
    secondary = Color(0xFF37474F),
    background = Color.White,
    surface = Color.White,
    onBackground = Color(0xFF101010),
    onSurface = Color(0xFF101010),
)

@Composable
fun CityLimitTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorScheme, content = content)
}
