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

// Non-arm64 link of the live probe. armeabi-v7a keeps no ESR_MAGIC record, and x86/x86_64 project
// no fault_code at all, so there is nothing equivalent to read. The explicit kUnsupportedArch
// outcome keeps the card informational instead of letting a missing record look like a clean one,
// and keeps the unsupported state scoped to the architectures that actually lack the channel.

#include "faultresidue/fault_record_probe.h"

namespace duckdetector::faultresidue {

    FaultRecordResult probe_fault_record() {
        FaultRecordResult result;
        result.outcome = FaultRecordOutcome::kUnsupportedArch;
        return result;
    }

}  // namespace duckdetector::faultresidue
