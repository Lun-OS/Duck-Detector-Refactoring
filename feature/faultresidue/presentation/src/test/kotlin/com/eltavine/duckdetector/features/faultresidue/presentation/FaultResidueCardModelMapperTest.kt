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

package com.eltavine.duckdetector.features.faultresidue.presentation

import com.eltavine.duckdetector.core.evidence.DetectionSeverity
import com.eltavine.duckdetector.core.evidence.DetectorStatus
import com.eltavine.duckdetector.core.evidence.InfoKind
import com.eltavine.duckdetector.core.report.ReportBlock
import com.eltavine.duckdetector.features.faultresidue.domain.FaultRecordAccess
import com.eltavine.duckdetector.features.faultresidue.domain.FaultResidueReading
import com.eltavine.duckdetector.features.faultresidue.domain.FaultResidueReport
import com.eltavine.duckdetector.features.faultresidue.domain.FaultResidueStage
import com.eltavine.duckdetector.features.faultresidue.domain.FaultResidueVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaultResidueCardModelMapperTest {

    private val mapper = FaultResidueCardModelMapper()

    private fun reading(far: ULong, esr: ULong, hasEsrRecord: Boolean = true) =
        FaultResidueReading(far, esr, hasEsrRecord)

    private fun ready(verdict: FaultResidueVerdict, reading: FaultResidueReading? = null) =
        FaultResidueReport(
            stage = FaultResidueStage.READY,
            verdict = verdict,
            reading = reading,
            access = if (reading == null) FaultRecordAccess.NOT_COLLECTED else FaultRecordAccess.READ,
        )

    @Test
    fun `an instruction abort raises the yellow row and card`() {
        val model = mapper.map(ready(FaultResidueVerdict.DETECTED_IABT, reading(0uL, 0x82000005uL)))

        assertEquals(DetectionSeverity.WARNING, model.status.severity)
        assertEquals("Fault residue observed", model.verdict)
        assertEquals(1, model.signalRows.size)
        assertEquals(DetectorStatus.warning(), model.signalRows.single().status)
        assertTrue(model.signalRows.single().detail!!.contains("esr=0x82000005"))
    }

    @Test
    fun `the detected finding routes the dashboard to the card with its mechanism`() {
        val model = mapper.map(ready(FaultResidueVerdict.DETECTED_IABT, reading(0uL, 0x82000005uL)))

        // The top-finding text must name the residue and the injector family so the dashboard reader
        // knows what the yellow card means without opening it, and must not dump the raw registers.
        val finding = model.findingDetail
        assertNotNull(finding)
        assertTrue(finding!!.contains("instruction abort"))
        assertTrue(finding.contains("fault-hijack"))
        assertTrue(finding.contains("Fault residue card"))
        assertFalse(finding.contains("far=0x"))
    }

    @Test
    fun `only the warning verdict publishes a top finding`() {
        assertNull(mapper.map(ready(FaultResidueVerdict.NORMAL_NO_RECORD, reading(0uL, 0uL, hasEsrRecord = false))).findingDetail)
        assertNull(mapper.map(ready(FaultResidueVerdict.INCONCLUSIVE_NULL_READ, reading(0uL, 0x92000006uL))).findingDetail)
        assertNull(mapper.map(ready(FaultResidueVerdict.UNSUPPORTED_ARCH)).findingDetail)
        assertNull(mapper.map(FaultResidueReport.loading()).findingDetail)
    }

    @Test
    fun `impact rows explain what the record proves and the channel limits`() {
        val detected = mapper.map(ready(FaultResidueVerdict.DETECTED_IABT, reading(0uL, 0x82000005uL)))
        val impact = detected.impactRows.first()

        assertEquals("Interpretation", impact.label)
        val interpretation = impact.detail
        assertNotNull(interpretation)
        assertTrue(interpretation!!.contains("Zygisk-style"))
        assertTrue(interpretation.contains("does not identify"))
        // The standing limits ride on every ready report, not only the warning one.
        assertEquals("Channel limits", detected.impactRows[1].label)
        assertTrue(detected.impactRows[1].detail!!.contains("one slot per thread"))
        val normal = mapper.map(ready(FaultResidueVerdict.NORMAL_NO_RECORD, reading(0uL, 0uL, hasEsrRecord = false)))
        assertTrue(normal.impactRows.first().detail!!.contains("not a clean bill of health"))
    }

    @Test
    fun `method rows explain the kernel mechanism and the self-administered read`() {
        val model = mapper.map(ready(FaultResidueVerdict.DETECTED_IABT, reading(0uL, 0x82000005uL)))
        val labels = model.methodRows.map { it.label }

        assertEquals(listOf("Kernel record", "Self-administered probe", "Fork-window sample"), labels)
        assertTrue(model.methodRows[0].detail!!.contains("fault_address"))
        assertTrue(model.methodRows[1].detail!!.contains("SIGWINCH"))
        assertTrue(model.methodRows[2].detail!!.contains("captured once"))
    }

    @Test
    fun `a zero record reads green without any signal rows`() {
        val model = mapper.map(ready(FaultResidueVerdict.NORMAL_NO_RECORD, reading(0uL, 0uL, hasEsrRecord = false)))

        assertEquals(DetectionSeverity.ALL_CLEAR, model.status.severity)
        assertEquals("No fault record", model.verdict)
        assertTrue(model.signalRows.isEmpty())
        assertEquals(DetectorStatus.allClear(), model.scanRows.first().status)
    }

    @Test
    fun `the null-read overwrite stays unjudged and names itself`() {
        val model = mapper.map(ready(FaultResidueVerdict.INCONCLUSIVE_NULL_READ, reading(0uL, 0x92000006uL)))

        assertEquals(DetectorStatus.info(InfoKind.SUPPORT), model.status)
        assertEquals("Record overwritten", model.verdict)
        assertTrue(model.signalRows.isEmpty())
        assertTrue(model.summary.contains("overwrites"))
    }

    @Test
    fun `unsupported and unread states stay informational`() {
        val unsupported = mapper.map(ready(FaultResidueVerdict.UNSUPPORTED_ARCH))
        val unavailable = mapper.map(
            ready(FaultResidueVerdict.UNAVAILABLE).copy(
                access = FaultRecordAccess.HANDLER_SILENT,
                unavailableReason = "the handler never ran",
            ),
        )

        assertEquals("Unsupported ABI", unsupported.verdict)
        assertEquals(DetectionSeverity.INFO, unsupported.status.severity)
        assertEquals("Not evaluated", unavailable.verdict)
        assertEquals(DetectionSeverity.INFO, unavailable.status.severity)
        assertEquals("the handler never ran", unavailable.summary)
    }

    @Test
    fun `loading and failed projections keep the scaffold states`() {
        assertEquals("Scanning", mapper.map(FaultResidueReport.loading()).verdict)
        assertEquals("Scan failed", mapper.map(FaultResidueReport.failed("boom")).verdict)
    }

    @Test
    fun `the export mirrors card verdict severity and rows`() {
        val model = mapper.map(ready(FaultResidueVerdict.DETECTED_IABT, reading(0uL, 0x82000005uL)))
        val export = model.toDetectorReport()

        assertEquals(model.verdict, export.verdict)
        assertEquals(model.status.severity, export.severity)
        val sectionTitles = export.blocks.filterIsInstance<ReportBlock.Rows>().map { it.title }
        assertEquals(listOf("Signals", "Impact", "Detection methods", "Scan"), sectionTitles)
        val signalRows = export.blocks.filterIsInstance<ReportBlock.Rows>().single { it.title == "Signals" }.rows
        val scanRows = export.blocks.filterIsInstance<ReportBlock.Rows>().single { it.title == "Scan" }.rows
        assertEquals(1, signalRows.size)
        assertEquals(2, scanRows.size)
    }
}
