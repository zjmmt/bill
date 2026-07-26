package dev.bill.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.bill.core.designsystem.theme.BillArtPalette
import kotlin.math.min

@Composable
fun PosterPanel(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.inverseSurface,
        contentColor = colors.inverseOnSurface,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Box {
            Canvas(Modifier.matchParentSize()) {
                val orbitRadius = min(size.width, size.height) * 0.34f
                val orbitCenter = Offset(size.width * 0.88f, size.height * 0.18f)

                drawLine(
                    color = colors.inverseOnSurface.copy(alpha = 0.12f),
                    start = Offset(size.width * 0.62f, 0f),
                    end = Offset(size.width * 0.62f, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
                drawLine(
                    color = colors.inverseOnSurface.copy(alpha = 0.12f),
                    start = Offset(0f, size.height * 0.72f),
                    end = Offset(size.width, size.height * 0.72f),
                    strokeWidth = 1.dp.toPx(),
                )

                val wedge = Path().apply {
                    moveTo(size.width * 0.82f, size.height)
                    lineTo(size.width, size.height * 0.70f)
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(wedge, color = colors.secondary)

                drawCircle(
                    color = colors.secondary.copy(alpha = 0.92f),
                    radius = orbitRadius,
                    center = orbitCenter,
                    style = Stroke(width = 2.dp.toPx()),
                )
                drawArc(
                    color = colors.inverseOnSurface.copy(alpha = 0.55f),
                    startAngle = 202f,
                    sweepAngle = 116f,
                    useCenter = false,
                    topLeft = Offset(
                        orbitCenter.x - orbitRadius * 0.72f,
                        orbitCenter.y - orbitRadius * 0.72f,
                    ),
                    size = Size(orbitRadius * 1.44f, orbitRadius * 1.44f),
                    style = Stroke(width = 1.dp.toPx()),
                )
                drawCircle(
                    color = BillArtPalette.SolarGold,
                    radius = 8.dp.toPx(),
                    center = Offset(size.width * 0.79f, size.height * 0.17f),
                )
            }

            Column(
                modifier = Modifier.padding(contentPadding),
                content = content,
            )
        }
    }
}

@Composable
fun SectionMarker(
    index: String,
    title: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = index,
            modifier = Modifier.clearAndSetSemantics { },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        Box(
            modifier = Modifier
                .clearAndSetSemantics { }
                .width(28.dp)
                .height(3.dp)
                .background(MaterialTheme.colorScheme.secondary),
        )
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
    }
}
