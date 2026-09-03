package dev.scoutr.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Raw color primitives; UI screens consume [ScoutrSemantic], never this object. */
object ScoutrPrimitive {
    val neutral0 = Color(0xFF08090B)
    val neutral10 = Color(0xFF0B0C0E)
    val neutral20 = Color(0xFF121316)
    val neutral40 = Color(0xFF16171B)
    val neutral60 = Color(0xFF1C1E22)
    val neutral80 = Color(0xFF1E2025)
    val neutral100 = Color(0xFF232529)

    val green400 = Color(0xFF8DF08D)
    val greenMuted = Color(0xFF5AAD64)
    val red400 = Color(0xFFE5484D)
    val redMuted = Color(0xFFC03A3F)
    val redCaption = Color(0xFFE95A5F)
    val amber400 = Color(0xFFE8B84B)
    val amberMuted = Color(0xFFC49A2E)
    val tealMuted = Color(0xFF2C6F72)
    val teal500 = Color(0xFF3A9A9E)
    val diffAdded = Color(0xFF3FC9E8)
    val diffAddedMuted = Color(0xFF2A9AB3)
    val diffRemoved = Color(0xFFFF6B70)
    val diffRemovedMuted = Color(0xFFC44A4F)

    val data100 = Color(0xFF0F3A3C)
    val data200 = Color(0xFF164E50)
    val data300 = Color(0xFF1E6466)
    val data400 = Color(0xFF2C7A7E)
    val data500 = teal500
    val data600 = Color(0xFF5ABAC0)
}

/** Five semantic color stops used by status, data, and diff components. */
data class ScoutrColorRole(
    val color: Color,
    val on: Color,
    val container: Color,
    val onContainer: Color,
    val muted: Color,
)

/** Semantic color contract for surfaces, statuses, usage data, and diffs. */
object ScoutrSemantic {
    val surfaceCard = ScoutrPrimitive.neutral40
    val surfaceSelected = ScoutrPrimitive.neutral60
    val surfaceSwipeBar = ScoutrPrimitive.neutral80
    val surfaceElevated = ScoutrPrimitive.neutral100

    val live = ScoutrColorRole(
        color = ScoutrPrimitive.green400,
        on = Color(0xFF04241A),
        container = Color(0xFF12301A),
        onContainer = Color(0xFFA6EFAD),
        muted = ScoutrPrimitive.greenMuted,
    )
    val critical = ScoutrColorRole(
        color = ScoutrPrimitive.red400,
        on = Color(0xFF3A0B0C),
        container = Color(0xFF3A1719),
        onContainer = Color(0xFFFFDAD9),
        muted = ScoutrPrimitive.redMuted,
    )
    val warning = ScoutrColorRole(
        color = ScoutrPrimitive.amber400,
        on = Color(0xFF261A00),
        container = Color(0xFF3B2900),
        onContainer = Color(0xFFFFDEA1),
        muted = ScoutrPrimitive.amberMuted,
    )
    val data = ScoutrColorRole(
        color = ScoutrPrimitive.teal500,
        on = Color(0xFF002324),
        container = Color(0xFF17383A),
        onContainer = Color(0xFFB9E8E9),
        muted = ScoutrPrimitive.tealMuted,
    )
    val diffAdded = ScoutrColorRole(
        color = ScoutrPrimitive.diffAdded,
        on = Color(0xFF00232B),
        container = Color(0xFF102B32),
        onContainer = Color(0xFFBDEFFD),
        muted = ScoutrPrimitive.diffAddedMuted,
    )
    val diffRemoved = ScoutrColorRole(
        color = ScoutrPrimitive.diffRemoved,
        on = Color(0xFF3A0B0C),
        container = Color(0xFF3A1719),
        onContainer = Color(0xFFFFDAD9),
        muted = ScoutrPrimitive.diffRemovedMuted,
    )

    val data100 = ScoutrPrimitive.data100
    val data200 = ScoutrPrimitive.data200
    val data300 = ScoutrPrimitive.data300
    val data400 = ScoutrPrimitive.data400
    val data500 = ScoutrPrimitive.data500
    val data600 = ScoutrPrimitive.data600
}

/** Component colors that must satisfy context-specific contrast across surface states. */
object ScoutrComponentTokens {
    /** Critical small text; AA on both card and selected-card surfaces. */
    val criticalCaption = ScoutrPrimitive.redCaption
}

/** Four-based spacing scale for reusable layout gaps and padding. */
object ScoutrSpace {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Corner radii for chips, cards, sheets, and dialogs. */
object ScoutrRadii {
    val sm = 6.dp
    val md = 8.dp
    val lg = 12.dp
    val xl = 16.dp
}

/** Border widths for ordinary outlines, selection, and strong status rings. */
object ScoutrBorder {
    val hairline = 1.dp
    val outline = 1.5.dp
    val strong = 2.dp
}
