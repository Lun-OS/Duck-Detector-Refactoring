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

package com.eltavine.duckdetector.features.faultresidue.data.repository

import com.eltavine.duckdetector.core.native.NativeCollectionOutcome
import com.eltavine.duckdetector.core.native.NativeCollectionStatus
import com.eltavine.duckdetector.features.faultresidue.data.native.FaultRecordWireOutcome
import com.eltavine.duckdetector.features.faultresidue.data.native.FaultResidueNativeSnapshot
import com.eltavine.duckdetector.features.faultresidue.domain.FaultRecordAccess
import com.eltavine.duckdetector.features.faultresidue.domain.FaultResidueVerdict
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaultResidueRepositoryTest {

    private fun repository(snapshot: FaultResidueNativeSnapshot?) =
        FaultResidueRepository(sampleProvider = { snapshot })

    @Test
    fun `a missing startup sample is unavailable, never clean`() = runBlocking {
        val report = repository(null).scan()

        assertEquals(FaultResidueVerdict.UNAVAILABLE, report.verdict)
        assertEquals(FaultRecordAccess.NOT_COLLECTED, report.access)
        assertNull(report.reading)
    }

    @Test
    fun `the detected reading maps to its verdict with the raw pair`() = runBlocking {
        val snapshot = FaultResidueNativeSnapshot(
            available = true,
            outcome = FaultRecordWireOutcome.READ,
            far = 0,
            esr = 0x82000005L,
            hasEsr = true,
        )

        val report = repository(snapshot).scan()

        assertEquals(FaultResidueVerdict.DETECTED_IABT, report.verdict)
        assertEquals(0x82000005uL, report.reading?.esr)
    }

    @Test
    fun `a high unsigned reading re-widens to the same pattern the kernel wrote`() = runBlocking {
        val snapshot = FaultResidueNativeSnapshot(
            available = true,
            outcome = FaultRecordWireOutcome.READ,
            far = (-99360000L), // the Long bit pattern of 0xFFFFFFFFFA13E300
            esr = 0x82000004L,
            hasEsr = true,
        )

        val report = repository(snapshot).scan()

        assertEquals(0xFFFFFFFFFA13E300uL, report.reading?.far)
        assertEquals(FaultResidueVerdict.DETECTED_IABT, report.verdict)
    }

    @Test
    fun `wire outcomes map onto their access states`() = runBlocking {
        val outcomes = mapOf(
            FaultRecordWireOutcome.UNSUPPORTED_ARCH to FaultRecordAccess.UNSUPPORTED_ARCH,
            FaultRecordWireOutcome.SETUP_FAILED to FaultRecordAccess.SETUP_FAILED,
            FaultRecordWireOutcome.HANDLER_SILENT to FaultRecordAccess.HANDLER_SILENT,
            FaultRecordWireOutcome.UNKNOWN to FaultRecordAccess.NOT_COLLECTED,
        )
        for ((wire, expected) in outcomes) {
            val snapshot = FaultResidueNativeSnapshot(
                available = false,
                outcome = wire,
                // Encoder defaults that would read "clean" if a non-READ outcome ever reached the
                // verdict rules.
                far = 0,
                esr = 0,
                hasEsr = false,
                note = "why",
            )

            val report = repository(snapshot).scan()

            assertEquals(expected, report.access)
            assertNull(report.reading)
            assertEquals("why", report.unavailableReason)
        }
    }

    @Test
    fun `a READ snapshot with a zero pair is a clean reading`() = runBlocking {
        val snapshot = FaultResidueNativeSnapshot(
            available = true,
            outcome = FaultRecordWireOutcome.READ,
            far = 0,
            esr = 0,
            hasEsr = false,
        )

        val report = repository(snapshot).scan()

        assertEquals(FaultResidueVerdict.NORMAL_NO_RECORD, report.verdict)
        assertEquals(FaultRecordAccess.READ, report.access)
    }

    @Test
    fun `an untrustworthy collection explains itself instead of reporting a record`() = runBlocking {
        val snapshot = FaultResidueNativeSnapshot.unavailable(
            NativeCollectionStatus(NativeCollectionOutcome.LIBRARY_UNAVAILABLE, "load failed"),
        )

        val report = repository(snapshot).scan()

        assertEquals(FaultResidueVerdict.UNAVAILABLE, report.verdict)
        assertEquals(FaultRecordAccess.NOT_COLLECTED, report.access)
        assertEquals(
            "Fault-record sample unavailable: load failed",
            report.unavailableReason,
        )
    }
}
