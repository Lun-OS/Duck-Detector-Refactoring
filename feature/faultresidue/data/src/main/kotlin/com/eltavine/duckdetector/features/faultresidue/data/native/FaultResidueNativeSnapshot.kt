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

package com.eltavine.duckdetector.features.faultresidue.data.native

import com.eltavine.duckdetector.core.native.NativeCollectionStatus

/**
 * How the native fault-record read ended, as named by the wire payload.
 *
 * The names travel as wire strings from `fault_record_probe.h` (OUTCOME=). Anything the encoder
 * adds later parses as [UNKNOWN], which the verdict rules treat like a failed read, not as a
 * clean device: an outcome this build cannot name never becomes evidence of absence.
 */
enum class FaultRecordWireOutcome {
    READ,
    UNSUPPORTED_ARCH,
    SETUP_FAILED,
    HANDLER_SILENT,
    UNKNOWN,
    ;

    companion object {
        fun fromWire(value: String): FaultRecordWireOutcome =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

/**
 * One wire snapshot of the kernel fault record of the sampling thread.
 *
 * [far] and [esr] are unsigned readings: the kernel projects raw register values, so a value the
 * native side printed above `Long.MAX_VALUE` is retained through the two's-complement wrap and the
 * domain rules re-widen it; the bridge must not clamp or reject it, because a high FAR is normal
 * evidence (an abort at an uncanonical address), not a wire fault.
 */
data class FaultResidueNativeSnapshot(
    /** True only when the probe actually read the fault record of this thread. */
    val available: Boolean = false,
    val outcome: FaultRecordWireOutcome = FaultRecordWireOutcome.UNKNOWN,
    val far: Long = 0,
    val esr: Long = 0,
    /** False when the frame carried no ESR record, which is how a zero fault_code reaches user space. */
    val hasEsr: Boolean = false,
    val note: String = "",
    /** Why the snapshot is or is not usable: a missing library and a rejected payload read differently. */
    val collection: NativeCollectionStatus = NativeCollectionStatus.Collected,
) {
    companion object {
        fun unavailable(status: NativeCollectionStatus): FaultResidueNativeSnapshot =
            FaultResidueNativeSnapshot(collection = status)
    }
}
