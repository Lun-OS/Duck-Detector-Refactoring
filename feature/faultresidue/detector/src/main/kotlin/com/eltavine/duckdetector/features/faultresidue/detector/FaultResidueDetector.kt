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

package com.eltavine.duckdetector.features.faultresidue.detector

import android.content.Context
import com.eltavine.duckdetector.core.detector.Detector
import com.eltavine.duckdetector.core.detector.DetectorScanner
import com.eltavine.duckdetector.core.detector.DetectorSpecificApi
import com.eltavine.duckdetector.core.evidence.DetectorId
import com.eltavine.duckdetector.core.report.DetectorReport
import com.eltavine.duckdetector.features.faultresidue.data.repository.FaultResidueRepository
import com.eltavine.duckdetector.features.faultresidue.domain.FaultResidueReport
import com.eltavine.duckdetector.features.faultresidue.presentation.FaultResidueCardModelMapper
import com.eltavine.duckdetector.features.faultresidue.presentation.model.FaultResidueCardModel
import com.eltavine.duckdetector.features.faultresidue.presentation.toDetectorReport

/**
 * Fault residue: looks for a recovered instruction abort in the kernel fault record this process inherited from its zygote parent, which fault-hijack injectors leave behind.
 *
 * Collected by [FaultResidueRepository], judged by `FaultResidueReport.toDetectorStatus()` in the domain
 * layer, and described by [FaultResidueCardModelMapper].
 */
@DetectorSpecificApi
public object FaultResidueDetector : Detector<FaultResidueReport, FaultResidueCardModel> {
    override val id: DetectorId = DetectorId("fault_residue")

    override fun createScanner(context: Context): DetectorScanner<FaultResidueReport> = FaultResidueRepository()

    override fun loadingReport(): FaultResidueReport = FaultResidueReport.loading()

    override fun describe(report: FaultResidueReport): FaultResidueCardModel = FaultResidueCardModelMapper().map(report)

    override fun export(model: FaultResidueCardModel): DetectorReport = model.toDetectorReport()
}
