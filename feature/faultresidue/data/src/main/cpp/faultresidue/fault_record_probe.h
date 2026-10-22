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

// Fault-residue probe: what it reads and why.
//
// The arm64 kernel keeps one fault record per thread (thread.fault_address plus
// thread.fault_code), written only when a memory fault is promoted to a delivered signal
// (arch/arm64/mm/fault.c, traps.c). fork copies the whole thread struct, so an app main thread
// inherits exactly what the zygote main thread carried at fork time, and the only user-space
// exit for those values is setup_sigframe's projection into a signal frame: the address
// unconditionally, the code as an esr_context TLV (magic 0x45535201) when non-zero.
//
// The anomaly this exposes: normal zygote startup and normal app code never promote an
// instruction-fetch fault. A fault-hijack injector (the family Zygisk and Zygisk-derived
// frameworks belong to) that overwrites code in the zygote and lets the CPU run into the
// resulting non-executable fetch leaves fault_code with EC 0x20 (instruction abort) behind in
// the inherited record. The abort's default consequence ends the process, so a live process
// carrying it means an external observer (a ptrace-style supervisor or an in-process handler
// installed from outside) caught and resumed the fault — residue the patcher does not scrub,
// because the values live in kernel thread state, not in any memory the injector maps.
//
// Known limits, why verdicts other than kRead exist: the slot is one entry per thread (any
// later promoted fault, e.g. the benign null-address read stock kernels produce, overwrites
// it), the record is per-thread and fork-copied (other processes' threads are never visible),
// injection that never faults (clean mmap-and-swap) leaves no record at all, and non-arm64
// ABIs project nothing (the stub returns kUnsupportedArch). See feature/faultresidue/EVIDENCE.md
// for the pinned kernel sources behind every sentence above.

#ifndef DUCKDETECTOR_FAULTRESIDUE_FAULT_RECORD_PROBE_H
#define DUCKDETECTOR_FAULTRESIDUE_FAULT_RECORD_PROBE_H

#include <cstdint>
#include <string>

namespace duckdetector::faultresidue {

    // How the fault-record read ended. Only kRead states anything about the device: the other
    // outcomes never become "no record", because a failed read says nothing.
    enum class FaultRecordOutcome {
        // The signal frame carried the kernel's saved fault pair; [FaultRecordResult] holds it.
        kRead,
        // No thread fault record is observable through the arm64 signal frame on this ABI.
        kUnsupportedArch,
        // The handler could not be installed, so no frame was ever produced.
        kSetupFailed,
        // The handler was installed but did not run, so the frame never returned; the record
        // stayed unread (for example SIGWINCH blocked on this thread).
        kHandlerSilent,
    };

    struct FaultRecordResult {
        FaultRecordOutcome outcome = FaultRecordOutcome::kSetupFailed;
        // thread.fault_address as the kernel projected it; valid only for kRead.
        uint64_t far = 0;
        // thread.fault_code from the ESR_MAGIC record; valid only for kRead with has_esr set.
        uint64_t esr = 0;
        // False when the frame carried no ESR_MAGIC record, which is how a zero fault_code reads.
        bool has_esr = false;
        // An errno or short diagnostic for the failure paths; escaped on the wire.
        std::string note;
    };

    // The wire name of an outcome; the Kotlin parser maps it back to a typed verdict.
    const char *fault_record_outcome_name(FaultRecordOutcome outcome);

    // Walks an arm64 signal-context __reserved region for the ESR_MAGIC record. Pure over bytes so
    // the kernel's TLV layout (fpsimd record first, END_MAGIC terminator) is testable on a host.
    bool find_esr_context(const unsigned char *reserved, size_t length, uint64_t *esr);

    // Projects one observed signal frame into a result: the fault address is unconditional and the
    // ESR code is present only when its record exists. The arm64 handler calls this with the real
    // frame, and the host test calls it with captured fixtures, so both share one projection rule.
    FaultRecordResult read_fault_record_from_frame(uint64_t fault_address, const unsigned char *reserved, size_t reserved_length);

    // Reads the current thread's kernel fault record through a self-directed SIGWINCH frame. On
    // non-arm64 builds this returns kUnsupportedArch without touching signal dispositions.
    FaultRecordResult probe_fault_record();

    // Encodes a result as the newline-separated KEY=value payload FaultResidueNativeBridge parses.
    std::string encode_fault_record_payload(const FaultRecordResult &result);

}  // namespace duckdetector::faultresidue

#endif  // DUCKDETECTOR_FAULTRESIDUE_FAULT_RECORD_PROBE_H
