package com.hamuk.swimpro.coach

import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiModelTest {
    @Test fun picksNewestStableFlash() {
        val models = listOf(
            "gemini-2.5-flash", "gemini-3.8-flash", "gemini-3.8-flash-lite", "gemini-3.8-pro",
            "gemini-3.9-flash-preview-0901", "gemini-3.8-flash-image", "text-embedding-004",
        )
        assertEquals("gemini-3.8-flash", GeminiClient.pickFlash(models))
    }

    @Test fun fallsBackToPreviewFlashWhenNoStable() {
        assertEquals("gemini-4.0-flash-preview", GeminiClient.pickFlash(listOf("gemini-4.0-flash-preview", "gemini-4.0-flash-lite", "gemini-4.0-pro")))
        assertEquals("gemini-4.0-pro", GeminiClient.pickFlash(listOf("gemini-4.0-pro", "gemini-4.0-flash-lite")))
    }
}

class UpdaterVersionTest {
    @Test fun comparesVersions() {
        org.junit.Assert.assertTrue(com.hamuk.swimpro.update.Updater.isNewer("0.10.0", "0.9.3"))
        org.junit.Assert.assertTrue(com.hamuk.swimpro.update.Updater.isNewer("1.0", "0.9.9"))
        org.junit.Assert.assertFalse(com.hamuk.swimpro.update.Updater.isNewer("0.2.0", "0.2.0"))
        org.junit.Assert.assertFalse(com.hamuk.swimpro.update.Updater.isNewer("0.1.9", "0.2.0"))
    }
}
