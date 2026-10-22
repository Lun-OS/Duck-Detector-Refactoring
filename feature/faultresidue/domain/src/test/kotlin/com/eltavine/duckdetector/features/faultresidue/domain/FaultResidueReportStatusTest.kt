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

package com.eltavine.duckdetector.features.faultresidue.domain

import com.eltavine.duckdetector.core.evidence.DetectionSeverity
import com.eltavine.duckdetector.core.evidence.InfoKind
import org.junit.Assert.assertEquals
import org.junit.Test

class FaultResidueReportStatusTest {

    private fun reading(far: ULong, esr: ULong, hasEsrRecord: Boolean = true) =
        FaultResidueReading(far, esr, hasEsrRecord)

    @Test
    fun `a zero pair is no record`() {
        // The kernel writes the pair together and omits the ESR record only for a zero code; both
        // spellings of "the slot holds nothing" clear the channel.
        assertEquals(
            FaultResidueVerdict.NORMAL_NO_RECORD,
            faultResidueVerdict(FaultRecordAccess.READ, reading(0uL, 0uL, hasEsrRecord = false)),
        )
        assertEquals(
            FaultResidueVerdict.NORMAL_NO_RECORD,
            faultResidueVerdict(FaultRecordAccess.READ, reading(0uL, 0uL)),
        )
    }

    @Test
    fun `an instruction abort is detected whatever address it names`() {
        // The pinned readings from the evidence record: the observed fault-hijack injectors differ
        // in FAR and DFSC but share EC 0x20, and only the class is a criterion (a stored value is
        // an injector implementation detail, never the rule).
        for (sample in listOf(
            reading(0x0uL, 0x82000005uL),             // abort at the null page
            reading(0xB400007DuL, 0x8200000FuL),      // abort inside a mapped, non-executable page
            reading(0xFFFFFFFFFA13E300uL, 0x82000004uL), // abort at an uncanonical high address
        )) {
            assertEquals(FaultResidueVerdict.DETECTED_IABT, faultResidueVerdict(FaultRecordAccess.READ, sample))
        }
    }

    @Test
    fun `a missing ESR record erases a nonzero fault address`() {
        // Without a record the kernel wrote no code, so EC cannot be an abort; judging on the raw
        // esr bits would fabricate a detection from a value the kernel never paired with the FAR.
        assertEquals(
            FaultResidueVerdict.INCONCLUSIVE_OTHER,
            faultResidueVerdict(FaultRecordAccess.READ, reading(0x1000uL, 0x82000005uL, hasEsrRecord = false)),
        )
    }

    @Test
    fun `a read abort at address zero is the inconclusive null read`() {
        // 0x92000006: EC 0x24 data abort, WnR=0 (read), FAR 0. Untouched 5.x/6.1 devices carry this
        // baseline, and because the slot holds one entry it overwrites any older record - so it is
        // never all-clear and never detected.
        assertEquals(
            FaultResidueVerdict.INCONCLUSIVE_NULL_READ,
            faultResidueVerdict(FaultRecordAccess.READ, reading(0uL, 0x92000006uL)),
        )
        // The same class at a non-zero address, or a write (WnR=1), is not this overwrite pattern.
        assertEquals(
            FaultResidueVerdict.INCONCLUSIVE_OTHER,
            faultResidueVerdict(FaultRecordAccess.READ, reading(0x100uL, 0x92000006uL)),
        )
        assertEquals(
            FaultResidueVerdict.INCONCLUSIVE_OTHER,
            faultResidueVerdict(FaultRecordAccess.READ, reading(0uL, 0x92000046uL)),
        )
    }

    @Test
    fun `records of other classes are unattributable`() {
        // A zero FAR with a zero ESR but a present record is the clean pair already covered; any
        // other class (here a BRK-style trap code) names an exception this signal cannot attribute.
        assertEquals(
            FaultResidueVerdict.INCONCLUSIVE_OTHER,
            faultResidueVerdict(FaultRecordAccess.READ, reading(0uL, 0xF00000E0uL)),
        )
        // A non-zero FAR with no record code: not a pair the kernel produces, and not zero-pair
        // clean either; it must not pass as normal.
        assertEquals(
            FaultResidueVerdict.INCONCLUSIVE_OTHER,
            faultResidueVerdict(FaultRecordAccess.READ, reading(0x7FuL, 0x0uL)),
        )
    }

    @Test
    fun `unread channels never read as clean`() {
        for (access in listOf(
            FaultRecordAccess.SETUP_FAILED,
            FaultRecordAccess.HANDLER_SILENT,
            FaultRecordAccess.NOT_COLLECTED,
        )) {
            assertEquals(FaultResidueVerdict.UNAVAILABLE, faultResidueVerdict(access, null))
        }
        // A READ that lost its reading is a wire contradiction; it degrades to unavailable, never
        // to the all-clear a null reading would fabricate.
        assertEquals(FaultResidueVerdict.UNAVAILABLE, faultResidueVerdict(FaultRecordAccess.READ, null))
    }

    @Test
    fun `unsupported arch is its own verdict`() {
        assertEquals(FaultResidueVerdict.UNSUPPORTED_ARCH, faultResidueVerdict(FaultRecordAccess.UNSUPPORTED_ARCH, null))
    }

    @Test
    fun `detected raises the yellow warning and a zero record raises the green all clear`() {
        val detected = FaultResidueReport(
            stage = FaultResidueStage.READY,
            verdict = FaultResidueVerdict.DETECTED_IABT,
            access = FaultRecordAccess.READ,
            reading = reading(0uL, 0x82000005uL),
        )
        assertEquals(DetectionSeverity.WARNING, detected.toDetectorStatus().severity)

        val clean = detected.copy(verdict = FaultResidueVerdict.NORMAL_NO_RECORD, reading = reading(0uL, 0uL, hasEsrRecord = false))
        assertEquals(DetectionSeverity.ALL_CLEAR, clean.toDetectorStatus().severity)
    }

    @Test
    fun `inconclusive, unsupported and unavailable stay unjudged info`() {
        for (verdict in listOf(
            FaultResidueVerdict.INCONCLUSIVE_NULL_READ,
            FaultResidueVerdict.INCONCLUSIVE_OTHER,
            FaultResidueVerdict.UNSUPPORTED_ARCH,
            FaultResidueVerdict.UNAVAILABLE,
            FaultResidueVerdict.NOT_EVALUATED,
        )) {
            val report = FaultResidueReport(stage = FaultResidueStage.READY, verdict = verdict)
            assertEquals(DetectionSeverity.INFO, report.toDetectorStatus().severity)
            assertEquals(InfoKind.SUPPORT, report.toDetectorStatus().infoKind)
        }
    }

    @Test
    fun `loading and failed stages keep their info kinds`() {
        assertEquals(InfoKind.SUPPORT, FaultResidueReport.loading().toDetectorStatus().infoKind)
        assertEquals(InfoKind.ERROR, FaultResidueReport.failed("boom").toDetectorStatus().infoKind)
    }
}
