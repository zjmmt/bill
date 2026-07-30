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

package com.paddle.ocr.preprocess

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.ceil

data class RecPreprocessResult(
    val tensorData: FloatArray,
    val shape: LongArray,
)

object RecPreprocessor {
    private const val FIXED_HEIGHT = 48
    private const val MAX_IMG_W = 3200

    fun preprocessBatch(crops: List<Mat>): RecPreprocessResult {
        require(crops.isNotEmpty()) { "Recognizer batch must contain at least one crop" }
        val ownedMats = Collections.newSetFromMap(IdentityHashMap<Mat, Boolean>())
        fun own(mat: Mat): Mat {
            ownedMats.add(mat)
            return mat
        }
        fun releaseOwned(mat: Mat) {
            if (ownedMats.remove(mat)) mat.release()
        }

        val resizedMats = mutableListOf<Mat>()
        val floatMats = mutableListOf<Mat>()
        val paddedMats = mutableListOf<Mat>()
        var outputTensor: FloatArray? = null
        var outputTransferred = false
        return try {
            // Convert BGR to RGB and resize to fixed height while preserving aspect ratio.
            for (crop in crops) {
                val rgb = own(Mat())
                Imgproc.cvtColor(crop, rgb, Imgproc.COLOR_BGR2RGB)
                val h = rgb.rows()
                val w = rgb.cols()
                require(h > 0 && w > 0) { "Recognizer crop must be non-empty" }
                val aspectRatio = w.toDouble() / h
                val newW = ceil(FIXED_HEIGHT * aspectRatio)
                    .toInt()
                    .coerceIn(1, MAX_IMG_W)
                val resized = own(Mat())
                Imgproc.resize(
                    rgb,
                    resized,
                    Size(newW.toDouble(), FIXED_HEIGHT.toDouble()),
                    0.0,
                    0.0,
                    Imgproc.INTER_LINEAR,
                )
                releaseOwned(rgb)
                resizedMats.add(resized)
            }

            // Normalize with (x / 255 - 0.5) / 0.5.
            for (mat in resizedMats) {
                val normalized = own(Mat(mat.rows(), mat.cols(), CvType.CV_32FC3))
                mat.convertTo(normalized, CvType.CV_32F)
                Core.divide(
                    normalized,
                    org.opencv.core.Scalar(127.5, 127.5, 127.5),
                    normalized,
                )
                Core.subtract(
                    normalized,
                    org.opencv.core.Scalar(1.0, 1.0, 1.0),
                    normalized,
                )
                floatMats.add(normalized)
                releaseOwned(mat)
            }
            resizedMats.clear()

            val maxW = floatMats.maxOf(Mat::cols)
            val batchSize = floatMats.size
            for (mat in floatMats) {
                if (mat.cols() == maxW) {
                    paddedMats.add(mat)
                } else {
                    val padded = own(
                        Mat(
                            FIXED_HEIGHT,
                            maxW,
                            CvType.CV_32FC3,
                            org.opencv.core.Scalar(0.0),
                        ),
                    )
                    val roi = own(padded.submat(0, FIXED_HEIGHT, 0, mat.cols()))
                    try {
                        mat.copyTo(roi)
                    } finally {
                        releaseOwned(roi)
                    }
                    releaseOwned(mat)
                    paddedMats.add(padded)
                }
            }
            floatMats.clear()

            val channelSize = Math.multiplyExact(FIXED_HEIGHT, maxW)
            val tensorData = FloatArray(
                Math.multiplyExact(Math.multiplyExact(batchSize, 3), channelSize),
            ).also { outputTensor = it }
            for (batchIndex in 0 until batchSize) {
                val mat = paddedMats[batchIndex]
                val channels = mutableListOf<Mat>()
                try {
                    Core.split(mat, channels)
                    require(channels.size == 3) {
                        "Recognizer input must contain exactly three channels"
                    }
                    for (channelIndex in 0..2) {
                        val buffer = FloatArray(channelSize)
                        try {
                            channels[channelIndex].get(0, 0, buffer)
                            System.arraycopy(
                                buffer,
                                0,
                                tensorData,
                                (batchIndex * 3 + channelIndex) * channelSize,
                                channelSize,
                            )
                        } finally {
                            buffer.fill(0f)
                        }
                    }
                } finally {
                    channels.forEach { channel ->
                        own(channel)
                        releaseOwned(channel)
                    }
                    releaseOwned(mat)
                }
            }
            paddedMats.clear()

            RecPreprocessResult(
                tensorData = tensorData,
                shape = longArrayOf(
                    batchSize.toLong(),
                    3,
                    FIXED_HEIGHT.toLong(),
                    maxW.toLong(),
                ),
            ).also { outputTransferred = true }
        } finally {
            if (!outputTransferred) outputTensor?.fill(0f)
            ownedMats.toList().forEach(::releaseOwned)
        }
    }
}
