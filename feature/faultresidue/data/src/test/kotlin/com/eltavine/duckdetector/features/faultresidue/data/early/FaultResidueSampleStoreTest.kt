/*
 * Copyright 2026 Duck Apps Contributor
 * If you have any questions, suggestions, or other inquiries, please email Eltavine <me@eltavine.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.eltavine.duckdetector.features.faultresidue.data.early

import com.eltavine.duckdetector.features.faultresidue.data.native.FaultRecordWireOutcome
import com.eltavine.duckdetector.features.faultresidue.data.native.FaultResidueNativeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FaultResidueSampleStoreTest {

    private val store = FaultResidueSampleStore

    @Before
    fun clearStore() {
        store.resetForTesting()
    }

    private fun sample(esr: Long) = FaultResidueNativeSnapshot(
        available = true,
        outcome = FaultRecordWireOutcome.READ,
        far = 0,
        esr = esr,
        hasEsr = true,
    )

    @Test
    fun `an empty store says nothing was sampled`() {
        assertFalse(store.hasSample)
        assertNull(store.currentSnapshot())
    }

    @Test
    fun `the first capture wins`() {
        store.capture(sample(0x82000005L))
        store.capture(sample(0L))

        // A later capture would describe exceptions the process itself produced; the earliest
        // reading is the only one that speaks about the fork-inherited record.
        assertEquals(0x82000005L, store.currentSnapshot()?.esr)
        assertTrue(store.hasSample)
    }

    @Test
    fun `a failed sample is still the process sample`() {
        val failed = FaultResidueNativeSnapshot(available = false, outcome = FaultRecordWireOutcome.HANDLER_SILENT)
        store.capture(failed)

        assertEquals(failed, store.currentSnapshot())
    }
}
