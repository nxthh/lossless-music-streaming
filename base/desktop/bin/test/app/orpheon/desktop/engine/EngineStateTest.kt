// Part of Orpheon (https://github.com/kota-zo/Orpheon), GPL-3.0.
package app.orpheon.desktop.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EngineStateTest {
    @Test fun startsIdle() {
        val s = EngineState()
        assertFalse(s.playing)
        assertEquals(0, s.positionMs)
    }
}
