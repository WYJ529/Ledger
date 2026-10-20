package com.example.cpen321application.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cpen321application.LedgerHttpRemote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt in with -Pandroid.testInstrumentationRunnerArguments.ledgerLiveFx=true and a local backend. No document upload or paid AI call. */
@RunWith(AndroidJUnit4::class)
class LedgerLiveFxTest {
    @Test fun emulatorCallsTheBackendForTheHistoricalWeekendExchangeRate() {
        assumeTrue("Explicit live FX integration opt-in is required.",
            InstrumentationRegistry.getArguments().getString("ledgerLiveFx") == "true")
        val quote = LedgerHttpRemote().quote("USD", "CAD", "2025-01-05")
        assertEquals("2025-01-03", quote.date)
        assertEquals("1.4411", quote.rate)
        assertTrue(quote.source.isNotBlank())
    }
}
