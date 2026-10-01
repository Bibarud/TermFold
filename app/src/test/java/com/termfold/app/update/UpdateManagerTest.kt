package com.termfold.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun `a higher version is newer`() {
        assertTrue(UpdateManager.isNewer("2.2.2", "2.2.1"))
        assertTrue(UpdateManager.isNewer("2.10.0", "2.9.9"))
        assertTrue(UpdateManager.isNewer("v3.0", "2.9.9"))
        assertTrue(UpdateManager.isNewer("2.2.1.1", "2.2.1"))
    }

    @Test
    fun `the same or a lower version is not newer`() {
        assertFalse(UpdateManager.isNewer("2.2.1", "2.2.1"))
        assertFalse(UpdateManager.isNewer("2.2", "2.2.0"))
        assertFalse(UpdateManager.isNewer("2.1.9", "2.2.0"))
    }
}
