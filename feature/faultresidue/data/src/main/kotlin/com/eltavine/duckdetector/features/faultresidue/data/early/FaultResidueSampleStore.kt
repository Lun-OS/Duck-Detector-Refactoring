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

import com.eltavine.duckdetector.features.faultresidue.data.native.FaultResidueNativeSnapshot

/**
 * The one early sample of this process's kernel fault record.
 *
 * The record is a single per-thread slot, and any promoted exception later in the process
 * overwrites it (EVIDENCE.md), so the value at scan time is only meaningful when it was read as
 * early as the process allows. The sampling provider captures at the earliest Java-side point of
 * every app process and keeps the result here; the scan reads this store and never re-samples,
 * because re-reading after the process ran would observe the process's own exceptions.
 */
object FaultResidueSampleStore {

    @Volatile
    private var snapshot: FaultResidueNativeSnapshot? = null

    /** True once a sample exists, even if the sample itself says the channel failed. */
    val hasSample: Boolean
        get() = snapshot != null

    fun capture(captured: FaultResidueNativeSnapshot) {
        // First capture wins: the store exists to pin the earliest reading, and later writes
        // would describe exceptions the process itself produced.
        if (snapshot == null) {
            snapshot = captured
        }
    }

    fun currentSnapshot(): FaultResidueNativeSnapshot? = snapshot

    internal fun resetForTesting() {
        snapshot = null
    }
}
