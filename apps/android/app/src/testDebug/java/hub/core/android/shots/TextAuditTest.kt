package hub.core.android.shots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The pseudo-locale audit on labels broken on purpose: it finds each, and not the good ones. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextAuditTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `finds a cut label and a lone letter, and passes an ellipsis`() {
        compose.setContent {
            Column {
                Text("Clipped label far too long", Modifier.width(60.dp), maxLines = 1, overflow = TextOverflow.Clip, fontSize = 14.sp)
                Text("Ellipsis label far too long", Modifier.width(60.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                Text("Tasks x", Modifier.width(46.dp), fontSize = 16.sp)
                Text("Fits", fontSize = 14.sp)
            }
        }
        val found = TextAudit.of(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        assertTrue(found.toString(), found.any { it.startsWith("cut without an ellipsis") && "Clipped" in it })
        assertTrue(found.toString(), found.any { it.startsWith("a lone letter") && "Tasks x" in it })
        assertEquals(found.toString(), 2, found.size)
    }
}
