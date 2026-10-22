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

import com.eltavine.duckdetector.core.evidence.DetectorStatus
import com.eltavine.duckdetector.core.evidence.InfoKind

/**
 * Maps the verdict onto card severity.
 *
 * Only two states carry a judgement, by design: the instruction-abort record is evidence of an
 * execution-flow hijack (warning), and a zero record read through a working channel clears this
 * one channel (all clear). Everything else - the null-read overwrite, unattributable records,
 * unsupported ABIs and failed reads - stays informational: "not observed here" is never
 * "clean", and "cannot look" is never "not detected".
 */
fun FaultResidueReport.toDetectorStatus(): DetectorStatus = when (stage) {
    FaultResidueStage.LOADING -> DetectorStatus.info(InfoKind.SUPPORT)
    FaultResidueStage.FAILED -> DetectorStatus.info(InfoKind.ERROR)
    FaultResidueStage.READY -> when (verdict) {
        FaultResidueVerdict.DETECTED_IABT -> DetectorStatus.warning()
        FaultResidueVerdict.NORMAL_NO_RECORD -> DetectorStatus.allClear()
        FaultResidueVerdict.NOT_EVALUATED,
        FaultResidueVerdict.INCONCLUSIVE_NULL_READ,
        FaultResidueVerdict.INCONCLUSIVE_OTHER,
        FaultResidueVerdict.UNSUPPORTED_ARCH,
        FaultResidueVerdict.UNAVAILABLE,
        -> DetectorStatus.info(InfoKind.SUPPORT)
    }
}
