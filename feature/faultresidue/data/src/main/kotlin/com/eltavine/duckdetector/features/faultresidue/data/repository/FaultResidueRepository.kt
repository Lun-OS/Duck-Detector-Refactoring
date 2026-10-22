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

import com.eltavine.duckdetector.core.detector.DetectorScanner
import com.eltavine.duckdetector.core.evidence.FailureName
import com.eltavine.duckdetector.features.faultresidue.data.early.FaultResidueSampleStore
import com.eltavine.duckdetector.features.faultresidue.data.native.FaultRecordWireOutcome
import com.eltavine.duckdetector.features.faultresidue.data.native.FaultResidueNativeSnapshot
import com.eltavine.duckdetector.features.faultresidue.domain.FaultRecordAccess
import com.eltavine.duckdetector.features.faultresidue.domain.FaultResidueReading
import com.eltavine.duckdetector.features.faultresidue.domain.FaultResidueReport
import com.eltavine.duckdetector.features.faultresidue.domain.FaultResidueStage
import com.eltavine.duckdetector.features.faultresidue.domain.faultResidueVerdict
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns the process's early fault-record sample into a report; the scan never re-samples.
 *
 * The kernel record is per thread, and a forked thread only carries the parent's slot contents at
 * fork time: the authoritative reading is the process main thread's, taken at the earliest startup
 * point by [com.eltavine.duckdetector.features.faultresidue.data.early.FaultResidueSamplingProvider].
 * A worker thread reading the record at scan time would report its own thread's exceptions as if
 * they were the zygote's residue, so a missing sample is reported unavailable instead.
 */
class FaultResidueRepository(
    private val sampleProvider: () -> FaultResidueNativeSnapshot? = FaultResidueSampleStore::currentSnapshot,
) : DetectorScanner<FaultResidueReport> {

    override suspend fun scan(): FaultResidueReport = withContext(Dispatchers.IO) {
        try {
            collect()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            FaultResidueReport.failed(FailureName.describe(failure))
        }
    }

    private fun collect(): FaultResidueReport {
        val snapshot = sampleProvider() ?: return FaultResidueReport(
            stage = FaultResidueStage.READY,
            verdict = faultResidueVerdict(FaultRecordAccess.NOT_COLLECTED, null),
            access = FaultRecordAccess.NOT_COLLECTED,
            unavailableReason = "No startup sample exists in this process, so the main thread's " +
                "inherited fault record was never read on its own thread.",
        )
        return toReport(snapshot)
    }

    private fun toReport(snapshot: FaultResidueNativeSnapshot): FaultResidueReport {
        val access = when {
            !snapshot.collection.isTrustworthy -> FaultRecordAccess.NOT_COLLECTED
            else -> when (snapshot.outcome) {
                FaultRecordWireOutcome.READ -> FaultRecordAccess.READ
                FaultRecordWireOutcome.UNSUPPORTED_ARCH -> FaultRecordAccess.UNSUPPORTED_ARCH
                FaultRecordWireOutcome.SETUP_FAILED -> FaultRecordAccess.SETUP_FAILED
                FaultRecordWireOutcome.HANDLER_SILENT -> FaultRecordAccess.HANDLER_SILENT
                FaultRecordWireOutcome.UNKNOWN -> FaultRecordAccess.NOT_COLLECTED
            }
        }
        // The wire fields are meaningful only for a READ frame; for every other outcome the
        // snapshot holds encoder defaults, and feeding those defaults to the verdict rules would
        // fabricate the zero pair that means "clean".
        val reading = if (access == FaultRecordAccess.READ) {
            FaultResidueReading(snapshot.far.toULong(), snapshot.esr.toULong(), snapshot.hasEsr)
        } else {
            null
        }
        return FaultResidueReport(
            stage = FaultResidueStage.READY,
            verdict = faultResidueVerdict(access, reading),
            reading = reading,
            access = access,
            unavailableReason = when {
                !snapshot.collection.isTrustworthy ->
                    snapshot.collection.explain("Fault-record sample unavailable")

                access != FaultRecordAccess.READ && snapshot.note.isNotBlank() -> snapshot.note
                access != FaultRecordAccess.READ -> "The fault-record channel did not observe this thread."
                else -> null
            },
        )
    }
}
