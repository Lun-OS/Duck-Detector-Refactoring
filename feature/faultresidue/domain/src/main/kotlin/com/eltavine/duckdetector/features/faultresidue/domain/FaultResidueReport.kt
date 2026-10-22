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

enum class FaultResidueStage {
    LOADING,
    READY,
    FAILED,
}

/** How the read of the thread fault record ended, independent of what the record contained. */
enum class FaultRecordAccess {
    /** The signal frame carried the kernel's saved pair; the reading describes it. */
    READ,

    /** The ABI keeps no fault record the signal frame can project, so nothing was asked. */
    UNSUPPORTED_ARCH,

    /** The handler could not be installed, so no frame was ever produced. */
    SETUP_FAILED,

    /** The handler was installed but did not run, so the record stayed unread. */
    HANDLER_SILENT,

    /** The snapshot itself was unusable (library missing or payload rejected). */
    NOT_COLLECTED,
}

/**
 * The kernel fault record as the frame projected it.
 *
 * Values stay unsigned because the kernel writes raw register contents: an FAR above
 * [Long.MAX_VALUE] is a normal uncanonical abort address, not a corruption.
 */
data class FaultResidueReading(
    val far: ULong,
    val esr: ULong,
    /** False when the frame carried no ESR record, which is how a zero fault_code reaches user space. */
    val hasEsrRecord: Boolean,
)

/** The judgement on one fault-record reading. */
enum class FaultResidueVerdict {
    /** No scan has produced a result yet. */
    NOT_EVALUATED,

    /** The record names a recovered instruction abort: execution was sent to a non-executable
     * address, its default consequence ends the process, and this process is alive. */
    DETECTED_IABT,

    /** The thread carries no promoted-exception record since it was forked. */
    NORMAL_NO_RECORD,

    /** The last promoted exception was a read at address zero, which on affected kernels is also
     * the stock baseline and overwrites any earlier record. */
    INCONCLUSIVE_NULL_READ,

    /** A promoted exception exists but cannot be attributed to this signal's mechanism. */
    INCONCLUSIVE_OTHER,

    /** The ABI cannot observe a fault record through the signal frame. */
    UNSUPPORTED_ARCH,

    /** The record could not be read, so the channel says nothing at all. */
    UNAVAILABLE,
}

/** Exception class of an ESR value (bits [31:26]) as the architecture manual defines the field. */
private fun exceptionClass(esr: ULong): ULong = (esr shr 26) and 0x3FuL

/** Data fault direction (bit [6]): non-zero means the trapped access was a write. */
private fun writeNotRead(esr: ULong): Boolean = ((esr shr 6) and 1uL) != 0uL

/**
 * Judges one fault-record reading by mechanism, never by stored values.
 *
 * EC 0x20 is the instruction abort: the CPU tried to fetch from an address the MMU rejected. A
 * zygote main thread does not execute such a fetch during normal startup, and its default
 * consequence terminates the process (SIGSEGV), so a live process carrying this record means an
 * external observer caught and resumed the fault (EVIDENCE.md, fault-hijack injection). Exact
 * FAR/DFSC values depend on where the hijack jumped and that page's table state, so they are
 * diagnostics, not criteria. EC 0x24 with a zero FAR and a read is the null dereference that also
 * occurs on untouched devices and, because the record slot holds one entry, overwrites any older
 * record; it must not read as clean.
 */
fun faultResidueVerdict(access: FaultRecordAccess, reading: FaultResidueReading?): FaultResidueVerdict = when (access) {
    FaultRecordAccess.UNSUPPORTED_ARCH -> FaultResidueVerdict.UNSUPPORTED_ARCH
    FaultRecordAccess.SETUP_FAILED,
    FaultRecordAccess.HANDLER_SILENT,
    FaultRecordAccess.NOT_COLLECTED,
    -> FaultResidueVerdict.UNAVAILABLE

    FaultRecordAccess.READ -> {
        if (reading == null) {
            FaultResidueVerdict.UNAVAILABLE
        } else {
            val esr = if (reading.hasEsrRecord) reading.esr else 0uL
            when {
                reading.far == 0uL && esr == 0uL -> FaultResidueVerdict.NORMAL_NO_RECORD
                exceptionClass(esr) == 0x20uL -> FaultResidueVerdict.DETECTED_IABT
                exceptionClass(esr) == 0x24uL && reading.far == 0uL && !writeNotRead(esr) ->
                    FaultResidueVerdict.INCONCLUSIVE_NULL_READ

                else -> FaultResidueVerdict.INCONCLUSIVE_OTHER
            }
        }
    }
}

/** What one scan observed and concluded; the judgement itself is [faultResidueVerdict]'s rule. */
data class FaultResidueReport(
    val stage: FaultResidueStage,
    val verdict: FaultResidueVerdict = FaultResidueVerdict.NOT_EVALUATED,
    /** The raw pair behind the verdict, retained as an auditable reading for the detail lines. */
    val reading: FaultResidueReading? = null,
    /** How the read ended; kept separate from the verdict so one failed read explains the next. */
    val access: FaultRecordAccess = FaultRecordAccess.NOT_COLLECTED,
    /** Why no record was read, when [access] is not [FaultRecordAccess.READ]. */
    val unavailableReason: String? = null,
    val errorMessage: String? = null,
) {
    companion object {
        fun loading(): FaultResidueReport = FaultResidueReport(stage = FaultResidueStage.LOADING)

        fun failed(message: String): FaultResidueReport = FaultResidueReport(
            stage = FaultResidueStage.FAILED,
            verdict = FaultResidueVerdict.UNAVAILABLE,
            errorMessage = message,
        )
    }
}
