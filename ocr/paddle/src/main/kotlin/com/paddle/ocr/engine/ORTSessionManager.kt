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
// Modified by the Bill project: telemetry is disabled before session creation and
// runtime parallelism is bounded for short, user-triggered mobile OCR jobs.

package com.paddle.ocr.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.model.OCRError
import java.nio.FloatBuffer

class ORTSessionManager(
    private val context: Context,
    private val config: EngineConfig,
) {
    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var detInputName: String = "x"
    private var recInputName: String = "x"
    var coldLoadTimeMs: Long = 0
        private set

    fun loadModels(detAssetPath: String, recAssetPath: String) {
        val loadStart = System.currentTimeMillis()
        val ortEnv = try {
            OrtEnvironment.getEnvironment().also { it.setTelemetry(false) }
        } catch (t: Throwable) {
            throw OCRError.ModelLoadFailed("ONNX Runtime", t)
        }
        env = ortEnv
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(config.numThreads)
            setInterOpNumThreads(1)
        }
        try {
            val loadedDetSession = createSessionFromAsset(
                ortEnv = ortEnv,
                opts = opts,
                assetPath = detAssetPath,
                modelName = "detection",
            )
            detSession = loadedDetSession
            val loadedRecSession = try {
                createSessionFromAsset(
                    ortEnv = ortEnv,
                    opts = opts,
                    assetPath = recAssetPath,
                    modelName = "recognition",
                )
            } catch (failure: Exception) {
                loadedDetSession.close()
                detSession = null
                throw failure
            } catch (linkage: LinkageError) {
                loadedDetSession.close()
                detSession = null
                throw linkage
            }
            recSession = loadedRecSession

            detInputName = try {
                loadedDetSession.inputNames.iterator().next()
            } catch (t: Throwable) {
                throw OCRError.ModelLoadFailed("detection", t)
            }
            recInputName = try {
                loadedRecSession.inputNames.iterator().next()
            } catch (t: Throwable) {
                throw OCRError.ModelLoadFailed("recognition", t)
            }
            coldLoadTimeMs = System.currentTimeMillis() - loadStart
        } finally {
            opts.close()
        }
    }

    private fun createSessionFromAsset(
        ortEnv: OrtEnvironment,
        opts: OrtSession.SessionOptions,
        assetPath: String,
        modelName: String,
    ): OrtSession {
        val modelBytes = readModelAsset(assetPath)
        return try {
            try {
                ortEnv.createSession(modelBytes, opts)
            } catch (failure: Exception) {
                throw OCRError.ModelLoadFailed(modelName, failure)
            } catch (linkage: LinkageError) {
                throw OCRError.ModelLoadFailed(modelName, linkage)
            }
        } finally {
            modelBytes.fill(0)
        }
    }

    fun runDetection(input: FloatArray, shape: LongArray): Pair<FloatArray, LongArray> {
        val session = detSession
            ?: throw OCRError.ModelLoadFailed("detection", Exception("Session not initialized"))
        val ortEnv = env
            ?: throw OCRError.ModelLoadFailed("detection", Exception("Environment not initialized"))
        return runSession(ortEnv, session, detInputName, input, shape, "detection")
    }

    fun runRecognition(input: FloatArray, shape: LongArray): Pair<FloatArray, LongArray> {
        val session = recSession
            ?: throw OCRError.ModelLoadFailed("recognition", Exception("Session not initialized"))
        val ortEnv = env
            ?: throw OCRError.ModelLoadFailed("recognition", Exception("Environment not initialized"))
        return runSession(ortEnv, session, recInputName, input, shape, "recognition")
    }

    fun release() {
        try {
            detSession?.close()
        } finally {
            detSession = null
            try {
                recSession?.close()
            } finally {
                recSession = null
                env = null
            }
        }
    }

    private fun readModelAsset(assetPath: String): ByteArray {
        return try {
            context.assets.open(assetPath).use { it.readBytes() }
        } catch (t: Throwable) {
            throw OCRError.ModelNotFound(assetPath, t)
        }
    }

    private fun runSession(
        ortEnv: OrtEnvironment,
        session: OrtSession,
        inputName: String,
        input: FloatArray,
        shape: LongArray,
        modelName: String,
    ): Pair<FloatArray, LongArray> {
        val tensor = try {
            OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(input), shape)
        } catch (t: Throwable) {
            throw OCRError.InferenceFailed(modelName, t)
        }
        val result = try {
            try {
                session.run(mapOf(inputName to tensor))
            } catch (t: Throwable) {
                throw OCRError.InferenceFailed(modelName, t)
            }
        } finally {
            tensor.close()
        }

        return try {
            try {
                val outputName = session.outputNames.iterator().next()
                val ortValue = result.get(outputName)
                    .orElseThrow { Exception("No output tensor found") }
                val outputTensor = ortValue as? OnnxTensor
                    ?: throw Exception("Output is not an ONNX tensor")
                val copiedOutput = copyFloatBuffer(outputTensor.floatBuffer)
                try {
                    Pair(copiedOutput, outputTensor.info.shape)
                } catch (failure: Throwable) {
                    copiedOutput.fill(0f)
                    throw failure
                }
            } catch (t: Throwable) {
                throw OCRError.InferenceFailed(modelName, t)
            }
        } finally {
            result.close()
        }
    }

    private fun copyFloatBuffer(buffer: FloatBuffer): FloatArray {
        val duplicate = buffer.duplicate()
        duplicate.rewind()
        val output = FloatArray(duplicate.remaining())
        return try {
            duplicate.get(output)
            output
        } catch (failure: Throwable) {
            output.fill(0f)
            throw failure
        }
    }
}
