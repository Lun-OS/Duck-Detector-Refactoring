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

import com.eltavine.duckdetector.core.native.NativeCollectionOutcome
import com.eltavine.duckdetector.core.native.NativeCollectionStatus
import com.eltavine.duckdetector.core.native.NativePayloadContractViolation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaultResidueNativeBridgeTest {

    private val bridge = FaultResidueNativeBridge()

    @Test
    fun `parses a clean zero reading`() {
        val snapshot = bridge.parse("AVAILABLE=1\nOUTCOME=READ\nFAR=0\nESR=0\nHAS_ESR=0\n")

        assertTrue(snapshot.available)
        assertEquals(FaultRecordWireOutcome.READ, snapshot.outcome)
        assertEquals(0L, snapshot.far)
        assertEquals(0L, snapshot.esr)
        assertFalse(snapshot.hasEsr)
    }

    @Test
    fun `parses the detected instruction abort reading`() {
        // The payload the arm64 probe emits for the observed fault-hijack record: esr 0x82000005.
        val snapshot = bridge.parse("AVAILABLE=1\nOUTCOME=READ\nFAR=0\nESR=2181038085\nHAS_ESR=1\n")

        assertTrue(snapshot.available)
        assertEquals(0x82000005L, snapshot.esr)
        assertTrue(snapshot.hasEsr)
    }

    @Test
    fun `a high unsigned reading survives the long wrap`() {
        // 0xFFFFFFFFFA13E300 = 18446744073610191616 does not fit a signed long. The encoder prints
        // the unsigned decimal, and the parser must keep the bit pattern rather than reject it:
        // re-widened in the domain it equals the same ULong the kernel wrote.
        val snapshot = bridge.parse("AVAILABLE=1\nOUTCOME=READ\nFAR=18446744073610191616\nESR=0\nHAS_ESR=0\n")

        assertEquals(0xFFFFFFFFFA13E300uL, snapshot.far.toULong())
    }

    @Test
    fun `an unsupported arch reading is unavailable but named`() {
        val snapshot = bridge.parse("AVAILABLE=0\nOUTCOME=UNSUPPORTED_ARCH\nFAR=0\nESR=0\nHAS_ESR=0\n")

        assertFalse(snapshot.available)
        assertEquals(FaultRecordWireOutcome.UNSUPPORTED_ARCH, snapshot.outcome)
    }

    @Test
    fun `a setup failure carries its escaped note`() {
        val snapshot = bridge.parse("AVAILABLE=0\nOUTCOME=SETUP_FAILED\nFAR=0\nESR=0\nHAS_ESR=0\nNOTE=sigaction failed (errno\\t=1)\n")

        assertEquals(FaultRecordWireOutcome.SETUP_FAILED, snapshot.outcome)
        assertEquals("sigaction failed (errno\t=1)", snapshot.note)
    }

    @Test
    fun `available requires the READ outcome not the flag alone`() {
        // A wire contradiction (older encoder, or a future outcome added without updating the
        // parser) must degrade to unavailable evidence rather than being read as a frame.
        val snapshot = bridge.parse("AVAILABLE=1\nOUTCOME=HANDLER_SILENT\nFAR=0\nESR=0\nHAS_ESR=0\n")

        assertFalse(snapshot.available)
        assertEquals(FaultRecordWireOutcome.HANDLER_SILENT, snapshot.outcome)
    }

    @Test
    fun `an unknown outcome name degrades instead of throwing`() {
        val snapshot = bridge.parse("AVAILABLE=1\nOUTCOME=SOMETHING_NEW\nFAR=0\nESR=0\nHAS_ESR=0\n")

        assertEquals(FaultRecordWireOutcome.UNKNOWN, snapshot.outcome)
        assertFalse(snapshot.available)
    }

    @Test
    fun `a non numeric reading field reads as zero`() {
        val snapshot = bridge.parse("AVAILABLE=1\nOUTCOME=READ\nFAR=not-a-number\nESR=0\nHAS_ESR=0\n")

        assertEquals(0L, snapshot.far)
    }

    @Test
    fun `a blank payload is a default snapshot`() {
        val snapshot = bridge.parse("")

        assertFalse(snapshot.available)
        assertEquals(FaultRecordWireOutcome.UNKNOWN, snapshot.outcome)
        assertTrue(snapshot.collection.isTrustworthy)
    }

    @Test(expected = NativePayloadContractViolation::class)
    fun `a payload without the outcome key is rejected`() {
        bridge.parse("AVAILABLE=1\nFAR=0\n")
    }

    @Test
    fun `an untrustworthy collection status is preserved`() {
        val snapshot = FaultResidueNativeSnapshot.unavailable(
            NativeCollectionStatus(NativeCollectionOutcome.LIBRARY_UNAVAILABLE, "libduckdetector missing"),
        )

        assertFalse(snapshot.available)
        assertEquals(NativeCollectionOutcome.LIBRARY_UNAVAILABLE, snapshot.collection.outcome)
    }

    @Test
    fun `the shared fixtures round-trip through the parser production uses`() {
        // These payloads are byte-for-byte the host-native encoder's output
        // (test_fault_record_host_native.py pins the fixtures against the real encode path), so
        // this test verifies production wire data against the production parser, not a hand copy.
        val zero = bridge.parse(requireNotNull(javaClass.getResource("/fault-record-read-zero.txt")).readText())
        assertTrue(zero.available)
        assertEquals(0L, zero.far)
        assertEquals(0L, zero.esr)
        assertFalse(zero.hasEsr)

        val detected = bridge.parse(requireNotNull(javaClass.getResource("/fault-record-read-iabt.txt")).readText())
        assertTrue(detected.available)
        assertEquals(0L, detected.far)
        assertEquals(0x82000005L, detected.esr)
        assertTrue(detected.hasEsr)

        val mapped = bridge.parse(requireNotNull(javaClass.getResource("/fault-record-read-mapped-far.txt")).readText())
        assertTrue(mapped.available)
        assertEquals(0x8200000FL, mapped.esr)
        // 0xB400007D fits a signed long and travels as its decimal.
        assertEquals(0xB400007DL, mapped.far)

        val unsupported = bridge.parse(requireNotNull(javaClass.getResource("/fault-record-unsupported.txt")).readText())
        assertFalse(unsupported.available)
        assertEquals(FaultRecordWireOutcome.UNSUPPORTED_ARCH, unsupported.outcome)

        val setupFailed = bridge.parse(requireNotNull(javaClass.getResource("/fault-record-setup-failed.txt")).readText())
        assertFalse(setupFailed.available)
        assertEquals(FaultRecordWireOutcome.SETUP_FAILED, setupFailed.outcome)
    }
}
