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

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.eltavine.duckdetector.features.faultresidue.data.native.FaultResidueNativeBridge

/**
 * Samples the kernel fault record at the earliest Java-side point an app process reaches.
 *
 * The framework installs app providers between `Application.attachBaseContext` and
 * `Application.onCreate` (ActivityThread.handleBindApplication), and the thread that runs
 * `onCreate` here is the process main thread, which is the thread that inherited the zygote main
 * thread's fault slot at fork. Sampling on this thread at this point is the closest observation of
 * the fork-time record a normal app can make, and it is required because the record is a single
 * slot: later promoted exceptions in the process overwrite it, and an injector's record can be
 * lost before any scan runs (EVIDENCE.md, visibility limits).
 *
 * The provider exposes no data; it exists only for this startup side effect, so every query path
 * returns an empty result rather than pretending to serve content.
 */
public class FaultResidueSamplingProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        // First capture wins and a failed capture still lands in the store as the sample this
        // process managed, so the scan can explain why it is informational. The provider never
        // blocks the startup path: the probe is one sigaction plus one synchronous signal.
        runCatching { FaultResidueSampleStore.capture(FaultResidueNativeBridge().collectSnapshot()) }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
