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

package com.eltavine.duckdetector.features.faultresidue.ui.card

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CrisisAlert
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.eltavine.duckdetector.core.ui.components.DetectorCardFrame
import com.eltavine.duckdetector.core.ui.components.DetectorDetailRowBlock
import com.eltavine.duckdetector.core.ui.components.DetectorHairline
import com.eltavine.duckdetector.core.ui.components.DetectorSectionFrame
import com.eltavine.duckdetector.core.ui.components.highestSectionSeverity
import com.eltavine.duckdetector.features.faultresidue.presentation.model.FaultResidueCardModel
import com.eltavine.duckdetector.features.faultresidue.presentation.model.FaultResidueDetailRowModel

@Composable
internal fun FaultResidueDetectorCard(
    model: FaultResidueCardModel,
    modifier: Modifier = Modifier,
) {
    DetectorCardFrame(
        title = model.title,
        subtitle = model.subtitle,
        status = model.status,
        verdict = model.verdict,
        summary = model.summary,
        leadingIcon = Icons.Rounded.Search,
        modifier = modifier,
    ) {
        FaultResidueRowSection(
            sectionKey = "faultresidue.signals",
            title = "Signals",
            icon = Icons.Rounded.Policy,
            rows = model.signalRows,
            showDivider = model.impactRows.isNotEmpty() || model.methodRows.isNotEmpty() || model.scanRows.isNotEmpty(),
        )
        FaultResidueRowSection(
            sectionKey = "faultresidue.impact",
            title = "Impact",
            icon = Icons.Rounded.CrisisAlert,
            rows = model.impactRows,
            showDivider = model.methodRows.isNotEmpty() || model.scanRows.isNotEmpty(),
        )
        FaultResidueRowSection(
            sectionKey = "faultresidue.methods",
            title = "Detection methods",
            icon = Icons.Rounded.Search,
            rows = model.methodRows,
            showDivider = model.scanRows.isNotEmpty(),
        )
        FaultResidueRowSection(
            sectionKey = "faultresidue.scan",
            title = "Scan",
            icon = Icons.Rounded.Info,
            rows = model.scanRows,
            showDivider = false,
        )
    }
}

@Composable
private fun FaultResidueRowSection(
    sectionKey: String,
    title: String,
    icon: ImageVector,
    rows: List<FaultResidueDetailRowModel>,
    showDivider: Boolean,
) {
    if (rows.isEmpty()) {
        return
    }
    DetectorSectionFrame(
        stateKey = sectionKey,
        title = title,
        icon = icon,
        severity = highestSectionSeverity(rows.map { it.status }),
        showDivider = showDivider,
    ) {
        rows.forEachIndexed { index, row ->
            DetectorDetailRowBlock(
                label = row.label,
                value = row.value,
                status = row.status,
                detail = row.detail,
            )
            if (index < rows.lastIndex) {
                DetectorHairline()
            }
        }
    }
}
