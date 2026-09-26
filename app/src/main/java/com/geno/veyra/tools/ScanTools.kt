package com.geno.veyra.tools

import android.graphics.BitmapFactory
import com.geno.veyra.gemini.protocol.FunctionDeclaration
import com.geno.veyra.glasses.GlassesCameraSource
import com.geno.veyra.glasses.GlassesManager
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * Captures one JPEG frame from the glasses camera and converts it into an
 * ML Kit [InputImage]. Returns the image, or an error message when capture
 * failed (no permission, camera unavailable).
 */
private suspend fun captureImage(
    glasses: GlassesManager,
    camera: GlassesCameraSource,
): Pair<InputImage?, String?> {
    if (!glasses.ensureCameraPermission()) {
        return null to "Camera permission denied."
    }
    val jpeg = camera.captureFrame()
        ?: return null to "Couldn't capture from the glasses camera."
    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        ?: return null to "Couldn't decode the camera frame."
    return InputImage.fromBitmap(bitmap, 0) to null
}

private fun errorResult(message: String?): JsonObject =
    buildJsonObject {
        put("status", "error")
        put("message", message ?: "Unknown error.")
    }

private fun barcodeFormatName(format: Int): String =
    when (format) {
        Barcode.FORMAT_QR_CODE -> "QR_CODE"
        Barcode.FORMAT_AZTEC -> "AZTEC"
        Barcode.FORMAT_CODE_128 -> "CODE_128"
        Barcode.FORMAT_CODE_39 -> "CODE_39"
        Barcode.FORMAT_CODE_93 -> "CODE_93"
        Barcode.FORMAT_CODABAR -> "CODABAR"
        Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
        Barcode.FORMAT_EAN_13 -> "EAN_13"
        Barcode.FORMAT_EAN_8 -> "EAN_8"
        Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_UPC_A -> "UPC_A"
        Barcode.FORMAT_UPC_E -> "UPC_E"
        else -> "UNKNOWN"
    }

/**
 * `scan_barcode` — scans the user's current view for QR codes and barcodes
 * using the glasses camera, on-device via ML Kit. Gated behind the
 * "QR Bar Scan" AI Settings toggle.
 */
class ScanBarcodeTool @Inject constructor(
    private val glasses: GlassesManager,
    private val camera: GlassesCameraSource,
) : AgentTool {

    override val name = "scan_barcode"

    override val gate = ToolGate.QR_SCAN

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Scans the user's current view for QR codes and " +
            "barcodes using the glasses camera. Use when the user asks " +
            "to scan a code, read a QR code, or check a product barcode. " +
            "Returns each code found with its format and value.",
        parameters = schema(
            """
            {
              "type": "object",
              "properties": {}
            }
            """,
        ),
    )

    override suspend fun execute(args: JsonObject): JsonObject {
        val (image, error) = captureImage(glasses, camera)
        if (image == null) return errorResult(error)

        val scanner = BarcodeScanning.getClient()
        return try {
            val barcodes = runCatching {
                scanner.process(image).await()
            }.getOrElse {
                return errorResult("Barcode scan failed.")
            }
            buildJsonObject {
                put("status", "ok")
                put(
                    "codes",
                    buildJsonArray {
                        barcodes.forEach { code ->
                            addJsonObject {
                                put(
                                    "format",
                                    barcodeFormatName(code.format),
                                )
                                code.rawValue?.let { put("value", it) }
                            }
                        }
                    },
                )
            }
        } finally {
            scanner.close()
        }
    }
}

/**
 * `read_text` — recognizes text visible in the user's current view using
 * the glasses camera, on-device via ML Kit (OCR). Gated behind the "OCR"
 * AI Settings toggle.
 */
class OcrTool @Inject constructor(
    private val glasses: GlassesManager,
    private val camera: GlassesCameraSource,
) : AgentTool {

    override val name = "read_text"

    override val gate = ToolGate.OCR

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Reads text visible in the user's current view " +
            "using the glasses camera (OCR). Use when the user asks " +
            "what some text says — a sign, label, document, menu, or " +
            "anything written. Returns the recognized text.",
        parameters = schema(
            """
            {
              "type": "object",
              "properties": {}
            }
            """,
        ),
    )

    override suspend fun execute(args: JsonObject): JsonObject {
        val (image, error) = captureImage(glasses, camera)
        if (image == null) return errorResult(error)

        val recognizer =
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT)
        return try {
            val visionText = runCatching {
                recognizer.process(image).await()
            }.getOrElse {
                return errorResult("Text recognition failed.")
            }
            buildJsonObject {
                put("status", "ok")
                put("text", visionText.text)
            }
        } finally {
            recognizer.close()
        }
    }
}
