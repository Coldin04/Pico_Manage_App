package com.cold04.picomanage

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import de.charlex.compose.FloatingActionButtonItem
import de.charlex.compose.SpeedDialState
import de.charlex.compose.SubSpeedDialFloatingActionButtons
import de.charlex.compose.rememberSpeedDialFloatingActionButtonState

data class SpeedDialAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/** Material 3 Speed Dial backed by the ch4rl3x SpeedDialFloatingActionButton library. */
@Composable
fun SpeedDialMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    actions: List<SpeedDialAction>,
    modifier: Modifier = Modifier,
    buttonContentDescription: String = "打开操作菜单",
) {
    val state = rememberSpeedDialFloatingActionButtonState()
    val items = remember(actions) {
        actions.map { action ->
            FloatingActionButtonItem(
                icon = action.icon,
                label = action.label,
                onFabItemClicked = action.onClick,
            )
        }
    }

    LaunchedEffect(expanded) {
        state.currentState = if (expanded) SpeedDialState.EXPANDED else SpeedDialState.COLLAPSED
    }
    LaunchedEffect(state.currentState) {
        onExpandedChange(state.currentState == SpeedDialState.EXPANDED)
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SubSpeedDialFloatingActionButtons(
            items = items,
            showLabels = true,
            state = state,
        )
        FloatingActionButton(
            onClick = { state.stateChange.invoke() },
        ) {
            Icon(Icons.Default.Add, contentDescription = buttonContentDescription)
        }
    }
}
