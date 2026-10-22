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

#include "faultresidue/fault_record_probe.h"

#include "common/payload_codec.h"

#include <algorithm>
#include <cstring>

namespace duckdetector::faultresidue {

    namespace {

        // The kernel marks the extra-signal-area records with 32-bit magics. ESR_MAGIC heads an
        // esr_context { _aarch64_ctx head; __u64 esr; }; END_MAGIC (0) terminates the list.
        // kernel/common arch/arm64/include/uapi/asm/sigcontext.h.
        constexpr uint32_t kEsrMagic = 0x45535201u;
        constexpr uint32_t kEndMagic = 0x00000000u;
        constexpr uint32_t kRecordHeaderSize = 8;
        constexpr uint32_t kEsrContextSize = 16;

        uint32_t read_le32(const unsigned char *bytes) {
            uint32_t value = 0;
            std::memcpy(&value, bytes, sizeof(value));
            return value;
        }

        uint64_t read_le64(const unsigned char *bytes) {
            uint64_t value = 0;
            std::memcpy(&value, bytes, sizeof(value));
            return value;
        }

    }  // namespace

    namespace {

        // Unsigned decimal, which std::to_string(uint64_t) is not on every libc spelling: the wire
        // must carry the kernel's raw pattern, and the Kotlin side re-widens it with
        // toULongOrNull(), so a signed wrap (a negative FAR) would reject a legitimate reading.
        std::string unsigned_decimal(uint64_t value) {
            if (value == 0) {
                return "0";
            }
            std::string digits;
            while (value != 0) {
                digits.push_back(static_cast<char>('0' + value % 10u));
                value /= 10u;
            }
            std::reverse(digits.begin(), digits.end());
            return digits;
        }

    }  // namespace

    const char *fault_record_outcome_name(FaultRecordOutcome outcome) {
        switch (outcome) {
            case FaultRecordOutcome::kRead:
                return "READ";
            case FaultRecordOutcome::kUnsupportedArch:
                return "UNSUPPORTED_ARCH";
            case FaultRecordOutcome::kSetupFailed:
                return "SETUP_FAILED";
            case FaultRecordOutcome::kHandlerSilent:
                return "HANDLER_SILENT";
        }
        return "READ";
    }

    bool find_esr_context(const unsigned char *reserved, size_t length, uint64_t *esr) {
        if (reserved == nullptr || esr == nullptr) {
            return false;
        }
        // Walk the record list the way the kernel writes it: fixed-size (magic, size) headers,
        // END_MAGIC last. A malformed or truncated record stops the walk rather than advancing
        // past the buffer, so a hostile or future layout cannot make this read out of bounds.
        // The value itself is loaded through memcpy because the record alignment inside the frame
        // is not guaranteed for this walk.
        size_t offset = 0;
        while (offset + kRecordHeaderSize <= length) {
            const uint32_t magic = read_le32(reserved + offset);
            const uint32_t size = read_le32(reserved + offset + 4);
            if (magic == kEndMagic) {
                return false;
            }
            if (size < kRecordHeaderSize || size > length - offset) {
                return false;
            }
            if (magic == kEsrMagic) {
                if (size < kEsrContextSize) {
                    return false;
                }
                *esr = read_le64(reserved + offset + 8);
                return true;
            }
            offset += size;
        }
        return false;
    }

    FaultRecordResult read_fault_record_from_frame(uint64_t fault_address, const unsigned char *reserved, size_t reserved_length) {
        // fault_address is projected unconditionally by the kernel; the ESR record is present only
        // when thread.fault_code was non-zero. Both rules are the kernel's own
        // (arch/arm64/kernel/signal.c, setup_sigframe / setup_esr_el1_context).
        FaultRecordResult result;
        result.outcome = FaultRecordOutcome::kRead;
        result.far = fault_address;
        uint64_t esr = 0;
        if (find_esr_context(reserved, reserved_length, &esr)) {
            result.esr = esr;
            result.has_esr = true;
        }
        return result;
    }

    std::string encode_fault_record_payload(const FaultRecordResult &result) {
        std::string payload;
        const bool readable = result.outcome == FaultRecordOutcome::kRead;
        payload.append(readable ? "AVAILABLE=1\n" : "AVAILABLE=0\n");
        payload.append("OUTCOME=");
        payload.append(fault_record_outcome_name(result.outcome));
        payload.push_back('\n');
        payload.append("FAR=" + unsigned_decimal(result.far) + "\n");
        payload.append("ESR=" + unsigned_decimal(result.esr) + "\n");
        payload.append(result.has_esr ? "HAS_ESR=1\n" : "HAS_ESR=0\n");
        if (!result.note.empty()) {
            payload.append("NOTE=");
            payload.append(duckdetector::common::escape_payload_value(result.note));
            payload.push_back('\n');
        }
        return payload;
    }

}  // namespace duckdetector::faultresidue
