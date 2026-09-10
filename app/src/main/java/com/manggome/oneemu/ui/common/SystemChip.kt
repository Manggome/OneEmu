package com.manggome.oneemu.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manggome.oneemu.model.SystemId

/** Small colored pill with the system's short name ("NDS", "GBA", ...). */
@Composable
fun SystemChip(system: SystemId, modifier: Modifier = Modifier, small: Boolean = false) {
    Text(
        text = system.shortName,
        modifier = modifier
            .background(system.color, RoundedCornerShape(if (small) 4.dp else 6.dp))
            .padding(horizontal = if (small) 4.dp else 7.dp, vertical = if (small) 1.dp else 2.dp),
        color = onColor(system.color),
        fontSize = if (small) 9.sp else 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        maxLines = 1,
    )
}

/** Black or white text depending on the chip's luminance so light chips (MAME yellow) stay readable. */
private fun onColor(bg: Color): Color =
    if (bg.luminance() > 0.5f) Color(0xFF1B1B1B) else Color.White

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
