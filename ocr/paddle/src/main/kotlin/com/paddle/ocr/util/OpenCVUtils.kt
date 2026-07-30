// Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
// Modified by the Bill project: initialization is synchronized so all capture paths
// share one deterministic native-library load.

package com.paddle.ocr.util

import android.content.Context

object OpenCVUtils {

    private var initialized = false

    @Synchronized
    fun init(@Suppress("UNUSED_PARAMETER") context: Context): Boolean {
        if (initialized) return true
        try {
            System.loadLibrary("opencv_java4")
            initialized = true
        } catch (_: UnsatisfiedLinkError) {
            // The caller converts native-library absence into a fixed, non-sensitive error.
        }
        return initialized
    }
}
