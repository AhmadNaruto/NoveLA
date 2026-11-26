package my.noveldokusha.features.reader.ui.settingDialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import my.noveldokusha.coreui.components.SlimListItem
import my.noveldokusha.coreui.theme.colorAccent
import my.noveldokusha.reader.R

@Composable
internal fun MoreSettingDialog(
    manualHighlightEnabled: Boolean = false,
    onManualHighlightEnabledChange: (Boolean) -> Unit = {},
) {
    ElevatedCard(
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 12.dp)
    ) {
        val scrollState = rememberScrollState()
        val isScrolling by remember { derivedStateOf { scrollState.isScrollInProgress } }
        Column(modifier = Modifier.padding(vertical = 16.dp).verticalScroll(scrollState)) {
            // Manual highlight
            SlimListItem(
                modifier = Modifier
                    .clickable(enabled = !isScrolling) { onManualHighlightEnabledChange(!manualHighlightEnabled) },
                headlineContent = {
                    Text(text = stringResource(id = R.string.manual_highlight))
                },
                leadingContent = {
                    Icon(
                        Icons.Outlined.Highlight,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingContent = {
                    Switch(
                        checked = manualHighlightEnabled,
                        onCheckedChange = onManualHighlightEnabledChange,
                        enabled = !isScrolling,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colorAccent(),
                            checkedTrackColor = colorAccent().copy(alpha = 0.4f),
                        )
                    )
                }
            )
        }
    }
}
