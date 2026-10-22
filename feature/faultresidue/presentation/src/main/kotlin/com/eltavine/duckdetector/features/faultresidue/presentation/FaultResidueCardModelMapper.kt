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

import com.eltavine.duckdetector.core.evidence.DetectorStatus
import com.eltavine.duckdetector.core.evidence.InfoKind
import com.eltavine.duckdetector.features.faultresidue.domain.FaultResidueReading
import com.eltavine.duckdetector.features.faultresidue.domain.FaultResidueReport
import com.eltavine.duckdetector.features.faultresidue.domain.FaultResidueStage
import com.eltavine.duckdetector.features.faultresidue.domain.FaultResidueVerdict
import com.eltavine.duckdetector.features.faultresidue.domain.toDetectorStatus
import com.eltavine.duckdetector.features.faultresidue.presentation.model.FaultResidueCardModel
import com.eltavine.duckdetector.features.faultresidue.presentation.model.FaultResidueDetailRowModel

/**
 * Words and rows of the Fault residue card; the verdict itself comes from the domain layer.
 *
 * Rows are chosen from the typed verdict, never from the card's own wording, and the raw record
 * is shown as hex because the readings are register values an auditor may want to re-derive the
 * exception class from (the domain deliberately does not judge on them).
 *
 * The impact rows state what an observed record does and does not prove, plus the standing
 * limits of the channel; the method rows state how the record was read.
 */
class FaultResidueCardModelMapper {

    fun map(report: FaultResidueReport): FaultResidueCardModel = FaultResidueCardModel(
        title = TITLE,
        subtitle = SUBTITLE,
        status = report.toDetectorStatus(),
        verdict = verdict(report),
        summary = summary(report),
        findingDetail = findingDetail(report),
        signalRows = signalRows(report),
        scanRows = scanRows(report),
        impactRows = impactRows(report),
        methodRows = methodRows(report),
    )

    private fun verdict(report: FaultResidueReport): String = when (report.stage) {
        FaultResidueStage.LOADING -> "Scanning"
        FaultResidueStage.FAILED -> "Scan failed"
        FaultResidueStage.READY -> when (report.verdict) {
            FaultResidueVerdict.DETECTED_IABT -> "Fault residue observed"
            FaultResidueVerdict.NORMAL_NO_RECORD -> "No fault record"
            FaultResidueVerdict.INCONCLUSIVE_NULL_READ -> "Record overwritten"
            FaultResidueVerdict.INCONCLUSIVE_OTHER -> "Record unattributable"
            FaultResidueVerdict.UNSUPPORTED_ARCH -> "Unsupported ABI"
            FaultResidueVerdict.UNAVAILABLE -> "Not evaluated"
            FaultResidueVerdict.NOT_EVALUATED -> "Not evaluated"
        }
    }

    private fun summary(report: FaultResidueReport): String = when (report.stage) {
        FaultResidueStage.LOADING -> "Collecting evidence."
        FaultResidueStage.FAILED -> report.errorMessage ?: "The scan failed before it read the fault record."
        FaultResidueStage.READY -> when (report.verdict) {
            FaultResidueVerdict.DETECTED_IABT ->
                "The fault record this process inherited names a recovered instruction abort: " +
                    "execution reached a non-executable address and an external observer resumed the process. " +
                    "Fault-hijack injection (for example a Zygisk-style loader that patches code, lets the " +
                    "resulting abort run, then resumes it) leaves exactly this residue. It does not identify " +
                    "which framework, and other instruction-fetch hijacks read the same way."

            FaultResidueVerdict.NORMAL_NO_RECORD ->
                "The main thread's inherited record is empty: no promoted exception since the " +
                    "fork this process can see. Fault-free injection methods leave no record here."

            FaultResidueVerdict.INCONCLUSIVE_NULL_READ ->
                "The record holds a null-address data read, which untouched devices also produce and " +
                    "which overwrites any older record. This channel cannot judge this device."

            FaultResidueVerdict.INCONCLUSIVE_OTHER ->
                "The record holds an exception class this probe cannot attribute."

            FaultResidueVerdict.UNSUPPORTED_ARCH ->
                "This ABI projects no fault record into the signal frame, so the channel has nothing to read."

            FaultResidueVerdict.UNAVAILABLE,
            FaultResidueVerdict.NOT_EVALUATED,
            -> report.unavailableReason
                ?: "The record was not read, so its silence says nothing about this device."
        }
    }

    /**
     * Short text for the dashboard's top findings; only the warning verdict is a finding there.
     * A routing hint, not a diagnostic dump: the hex pair stays in the card's signal row.
     */
    private fun findingDetail(report: FaultResidueReport): String? {
        if (report.stage != FaultResidueStage.READY || report.verdict != FaultResidueVerdict.DETECTED_IABT) {
            return null
        }
        return "Inherited kernel fault record names a recovered instruction abort, the residue a " +
            "fault-hijack injector (Zygisk-style) leaves behind; open the Fault residue card for the " +
            "raw far/esr pair."
    }

    private fun signalRows(report: FaultResidueReport): List<FaultResidueDetailRowModel> {
        // DETECTED_IABT only ever comes with the reading it judged, but the model is nullable, so
        // the row renders from a local smart-cast copy rather than a chain of safe calls that
        // would print "0xnull" if the pair were ever lost.
        val reading = report.reading ?: return emptyList()
        if (report.stage != FaultResidueStage.READY || report.verdict != FaultResidueVerdict.DETECTED_IABT) {
            return emptyList()
        }
        return listOf(
            FaultResidueDetailRowModel(
                label = "Recovered instruction abort",
                value = "Inherited record",
                status = DetectorStatus.warning(),
                detail = "EC 0x20 means the CPU tried to fetch an instruction from an address the MMU " +
                    "rejected; its default consequence ends the process, so a live process carrying it had " +
                    "an external observer resume the fault. The exact address and fault status depend on " +
                    "where the hijack jumped, so they are diagnostics, not criteria. " +
                    reading.recordLine(withExceptionClass = true),
            ),
        )
    }

    private fun scanRows(report: FaultResidueReport): List<FaultResidueDetailRowModel> {
        if (report.stage != FaultResidueStage.READY) {
            return emptyList()
        }
        val reading = report.reading.takeIf { report.verdict in READ_VERDICTS }
        return listOf(
            FaultResidueDetailRowModel(
                label = "Fault channel",
                value = if (reading != null) "Read" else "Not read",
                status = if (reading != null) DetectorStatus.allClear() else DetectorStatus.info(InfoKind.SUPPORT),
                detail = report.unavailableReason,
            ),
            FaultResidueDetailRowModel(
                label = "Inherited record",
                value = reading?.recordLine(withExceptionClass = false) ?: "-",
                status = report.toDetectorStatus(),
            ),
        )
    }

    /** What the observed record does and does not prove, plus the standing limits of the channel. */
    private fun impactRows(report: FaultResidueReport): List<FaultResidueDetailRowModel> {
        if (report.stage != FaultResidueStage.READY) {
            return emptyList()
        }
        val support = DetectorStatus.info(InfoKind.SUPPORT)
        return listOf(
            FaultResidueDetailRowModel(
                label = "Interpretation",
                value = if (report.verdict == FaultResidueVerdict.DETECTED_IABT) "Recovered instruction abort" else "No attribution",
                status = report.toDetectorStatus(),
                detail = when (report.verdict) {
                    FaultResidueVerdict.DETECTED_IABT ->
                        "A fault-hijack injector (for example a Zygisk-style loader) that patches code " +
                            "during specialize and lets the resulting abort run before an external handler " +
                            "resumes it leaves exactly this residue. It does not identify which framework, " +
                            "and any other mechanism that hijacks instruction fetch reads the same way. " +
                            "Ordinary fault recovery cannot produce this record: an in-process handler " +
                            "recovering its own data fault writes a data-abort class, never an instruction " +
                            "abort, so the pair is abnormal by its exception class alone."

                    FaultResidueVerdict.NORMAL_NO_RECORD ->
                        "An empty record means no promoted exception is visible on the main thread since " +
                            "this process was forked. Fault-free injection (mmap-then-swap, ptrace without " +
                            "hijacked fetch) leaves no record here, so this is not a clean bill of health; " +
                            "the card's other signals carry the rest of the judgement."

                    FaultResidueVerdict.INCONCLUSIVE_NULL_READ ->
                        "The record slot keeps one entry per thread. A null-address data read — which " +
                            "untouched devices also produce, especially on unrooted 5.x and 6.1 kernels — " +
                            "overwrote any older record, so the channel expired for this device and says " +
                            "nothing in either direction."

                    FaultResidueVerdict.INCONCLUSIVE_OTHER ->
                        "A promoted exception exists, but this probe attributes only instruction aborts " +
                            "(EC 0x20) and the null-read baseline (EC 0x24 at address zero). Anything else " +
                            "is left unjudged instead of being forced into a colour."

                    FaultResidueVerdict.UNSUPPORTED_ARCH ->
                        "Only arm64 projects the kernel fault record into the signal frame. Other ABIs " +
                            "cannot use this channel, so the card never shows a verdict there."

                    FaultResidueVerdict.UNAVAILABLE,
                    FaultResidueVerdict.NOT_EVALUATED,
                    -> "The record was never read. Absence of evidence here is evidence of nothing; " +
                        "the fault channel's state line says why."
                },
            ),
            FaultResidueDetailRowModel(
                label = "Channel limits",
                value = "Per-thread, overwrite-prone",
                status = support,
                detail = "The record is one slot per thread: any later promoted exception (including a " +
                    "benign null read) overwrites it, so the probe reads as early as possible. The value " +
                    "seen here is the main thread's — inherited from the zygote main thread at fork — and " +
                    "other threads of other processes are never observed. Detection is arm64-only.",
            ),
        )
    }

    /** How the record was read, stated in terms the reader can verify against the kernel sources. */
    private fun methodRows(report: FaultResidueReport): List<FaultResidueDetailRowModel> {
        if (report.stage != FaultResidueStage.READY) {
            return emptyList()
        }
        val read = report.reading != null
        val readStatus = if (read) DetectorStatus.info(InfoKind.SUPPORT) else report.toDetectorStatus()
        return listOf(
            FaultResidueDetailRowModel(
                label = "Kernel record",
                value = if (read) "Inherited pair read" else "Not collected",
                status = readStatus,
                detail = "arch/arm64 writes thread.fault_address and thread.fault_code when a fault is " +
                    "promoted to a signal, and a fork copies them into the child, so an app main thread " +
                    "inherits whatever the zygote main thread carried. User space sees them only through " +
                    "setup_sigframe's projection: the address always, the code as an esr_context record " +
                    "(magic 0x45535201) inside the signal frame's reserved area when non-zero.",
            ),
            FaultResidueDetailRowModel(
                label = "Self-administered probe",
                value = if (read) "SIGWINCH handler" else "See channel state",
                status = readStatus,
                detail = "This thread raises SIGWINCH to itself with tkill and reads the pair from the " +
                    "ucontext its own SA_SIGINFO handler receives, restoring the previous handler " +
                    "immediately. SIGWINCH carries no default action, so the raise is harmless even if no " +
                    "handler ever runs, and no other process or thread is signalled.",
            ),
            FaultResidueDetailRowModel(
                label = "Fork-window sample",
                value = "First capture wins",
                status = DetectorStatus.info(InfoKind.SUPPORT),
                detail = "The record is captured once at the earliest in-process point after this app was " +
                    "forked from the zygote, through an automatic initialization provider, before the " +
                    "main application class runs. Later overwrites cannot change the evidence, and a " +
                    "rescan never re-reads the stale value.",
            ),
        )
    }

    /** The raw pair as hex: register values an auditor may re-derive the exception class from. */
    private fun FaultResidueReading.recordLine(withExceptionClass: Boolean): String = buildString {
        append("far=0x").append(far.toString(16))
        append(" esr=0x").append(esr.toString(16))
        if (withExceptionClass) {
            append(" ec=0x").append(((esr shr 26) and 0x3FuL).toString(16))
        }
    }

    private companion object {
        const val TITLE = "Fault residue"
        const val SUBTITLE = "Inherited kernel fault record"
        val READ_VERDICTS = setOf(
            FaultResidueVerdict.DETECTED_IABT,
            FaultResidueVerdict.NORMAL_NO_RECORD,
            FaultResidueVerdict.INCONCLUSIVE_NULL_READ,
            FaultResidueVerdict.INCONCLUSIVE_OTHER,
        )
    }
}
