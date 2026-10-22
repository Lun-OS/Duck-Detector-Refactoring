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

import com.eltavine.duckdetector.core.native.NativePayloadCodec
import com.eltavine.duckdetector.core.native.NativePayloadContract
import com.eltavine.duckdetector.core.native.NativeSnapshotCollector

/** Reads the `KEY=value` payload that native_bridge.cpp in the faultresidue native unit writes. */
class FaultResidueNativeBridge(
    private val collector: NativeSnapshotCollector = NativeSnapshotCollector.Default,
) {

    fun collectSnapshot(): FaultResidueNativeSnapshot = collector.collect(
        readPayload = ::nativeCollectSnapshot,
        parse = ::parse,
        unavailable = FaultResidueNativeSnapshot::unavailable,
    )

    internal fun parse(raw: String): FaultResidueNativeSnapshot {
        if (raw.isBlank()) {
            return FaultResidueNativeSnapshot()
        }
        NativePayloadContract.requireKeys(raw, "AVAILABLE", "OUTCOME")

        val entries = raw.lineSequence()
            .filter { '=' in it }
            .map { it.substringBefore('=').trim() to it.substringAfter('=') }
            .toMap()

        val outcome = FaultRecordWireOutcome.fromWire(entries["OUTCOME"].orEmpty())
        val available = NativePayloadCodec.decodeFlag(entries["AVAILABLE"]) &&
            outcome == FaultRecordWireOutcome.READ

        return FaultResidueNativeSnapshot(
            available = available,
            outcome = outcome,
            far = unsignedField(entries["FAR"]),
            esr = unsignedField(entries["ESR"]),
            hasEsr = NativePayloadCodec.decodeFlag(entries["HAS_ESR"]),
            note = entries["NOTE"]?.let(NativePayloadCodec::decodeValue).orEmpty(),
        )
    }

    /**
     * Decodes a native unsigned 64-bit decimal. The kernel projects raw FAR/ESR register values,
     * so a reading above [Long.MAX_VALUE] is legitimate evidence, not a malformed payload: it is
     * kept as the two's-complement bit pattern and the domain rules re-widen it. A value that is
     * not a number at all degrades to zero, which the verdicts then read through the record's
     * own flags rather than trusting.
     */
    private fun unsignedField(value: String?): Long =
        value?.toULongOrNull()?.toLong() ?: 0

    private external fun nativeCollectSnapshot(): String
}
