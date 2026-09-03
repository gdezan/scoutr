package dev.scoutr.app.ui.components

import dev.scoutr.app.ui.theme.ScoutrSpace
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Chat is prose, so it reads tighter than [ReadableContentColumn]'s 960dp scan
 * measure. Applied at every width, and it caps the wide window's detail pane too.
 */
val ChatProseMeasure = 600.dp

/** Centers scan-oriented screen content at 960dp on expanded windows and 12dp on phones. */
@Composable
fun ReadableContentColumn(
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    contentTag: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        val horizontalGutter = if (maxWidth >= 1008.dp) ScoutrSpace.xl else ScoutrSpace.md
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalGutter),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 960.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .then(contentModifier)
                    .let { modifier -> contentTag?.let(modifier::testTag) ?: modifier },
                content = content,
            )
        }
    }
}
