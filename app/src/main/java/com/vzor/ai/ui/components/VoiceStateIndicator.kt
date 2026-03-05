package com.vzor.ai.ui.components

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.vzor.ai.domain.model.VoiceState
import com.vzor.ai.ui.theme.VzorBlue
import com.vzor.ai.ui.theme.VzorTheme

@Composable
fun VoiceStateIndicator(
    state: VoiceState,
    modifier: Modifier = Modifier
) {
    when (state) {
        VoiceState.IDLE -> IdleDot(modifier)
        VoiceState.LISTENING -> ListeningPulse(modifier)
        VoiceState.PROCESSING -> ProcessingSpinner(modifier)
        VoiceState.GENERATING -> GeneratingDots(modifier)
        VoiceState.RESPONDING -> RespondingBars(modifier)
        VoiceState.CONFIRMING -> ConfirmingPulse(modifier)
        VoiceState.ERROR -> StaticDot(color = MaterialTheme.colorScheme.error, modifier = modifier)
        VoiceState.SUSPENDED -> StaticDot(color = Color.Gray, modifier = modifier)
    }
}

@Composable
private fun IdleDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(VzorBlue)
    )
}

@Composable
private fun StaticDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun ListeningPulse(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "listening_pulse")

    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Box(
        modifier = modifier.size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outer pulsing ring
        Box(
            modifier = Modifier
                .size(24.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(VzorBlue.copy(alpha = alpha * 0.3f))
        )
        // Inner solid dot
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(VzorBlue)
        )
    }
}

@Composable
private fun ProcessingSpinner(modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        modifier = modifier.size(18.dp),
        strokeWidth = 2.dp,
        color = VzorBlue
    )
}

@Composable
private fun GeneratingDots(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "generating_dots")

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.5f,
                targetValue = 1.2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 500,
                        delayMillis = index * 150,
                        easing = LinearEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_scale_$index"
            )

            Box(
                modifier = Modifier
                    .size(8.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(VzorBlue)
            )
        }
    }
}

@Composable
private fun RespondingBars(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "responding_bars")

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(5) { index ->
            val heightFraction by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 400,
                        delayMillis = index * 80,
                        easing = LinearEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar_height_$index"
            )

            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = (18 * heightFraction).dp)
                    .clip(CircleShape)
                    .background(VzorBlue)
            )
        }
    }
}

@Composable
private fun ConfirmingPulse(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "confirming_pulse")

    val color by infiniteTransition.animateColor(
        initialValue = VzorBlue,
        targetValue = Color(0xFFFFA726),
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "confirm_color"
    )

    Box(
        modifier = modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Preview(showBackground = true)
@Composable
private fun VoiceStateIndicatorPreview() {
    VzorTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            VoiceStateIndicator(state = VoiceState.IDLE)
            VoiceStateIndicator(state = VoiceState.LISTENING)
            VoiceStateIndicator(state = VoiceState.PROCESSING)
            VoiceStateIndicator(state = VoiceState.GENERATING)
            VoiceStateIndicator(state = VoiceState.RESPONDING)
            VoiceStateIndicator(state = VoiceState.ERROR)
            VoiceStateIndicator(state = VoiceState.SUSPENDED)
        }
    }
}
