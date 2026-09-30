package com.geno.veyra.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.geno.veyra.gemini.protocol.FunctionDeclaration
import com.geno.veyra.glasses.GlassesCameraSource
import com.geno.veyra.glasses.GlassesManager
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the most recently scanned UPI payment URL.
 *
 * [OpenPaymentAppTool] only ever opens the URL produced by
 * [ScanUpiQrTool] — it takes no URL parameter, so the model cannot
 * inject an arbitrary payment destination.
 */
@Singleton
class LastScannedUpi @Inject constructor() {
    @Volatile
    var upiUrl: String? = null
}

/** Parsed fields from a `upi://pay?...` QR code. */
data class UpiPaymentData(
    val payeeAddress: String,
    val payeeName: String?,
    val amount: String?,
    val currency: String?,
    val note: String?,
    val rawUrl: String,
)

/**
 * Parses a `upi://pay` URL into its payment fields.
 * Returns null when the value is not a UPI payment URL.
 */
fun parseUpiUrl(rawValue: String): UpiPaymentData? {
    if (!rawValue.startsWith("upi://pay", ignoreCase = true)) {
        return null
    }
    return try {
        val uri = Uri.parse(rawValue)
        val payeeAddress = uri.getQueryParameter("pa") ?: return null
        UpiPaymentData(
            payeeAddress = payeeAddress,
            payeeName = uri.getQueryParameter("pn"),
            amount = uri.getQueryParameter("am"),
            currency = uri.getQueryParameter("cu") ?: "INR",
            note = uri.getQueryParameter("tn"),
            rawUrl = rawValue,
        )
    } catch (e: Exception) {
        Log.w("UpiPayTools", "Failed to parse UPI URL", e)
        null
    }
}

private fun upiError(message: String): JsonObject =
    buildJsonObject {
        put("status", "error")
        put("message", message)
    }

/**
 * `scan_upi_qr` — captures the user's current view from the glasses
 * camera and looks for a UPI payment QR code, on-device via ML Kit.
 *
 * Returns the parsed payment details (payee, amount, currency, note).
 * The raw UPI URL is kept for [OpenPaymentAppTool]; it is NOT returned
 * to the model.
 *
 * Gated behind the "UPI Payments" AI Settings toggle.
 */
class ScanUpiQrTool @Inject constructor(
    private val glasses: GlassesManager,
    private val camera: GlassesCameraSource,
    private val lastScannedUpi: LastScannedUpi,
) : AgentTool {

    override val name = "scan_upi_qr"

    override val gate = ToolGate.UPI_PAY

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Scans the user's current view for a UPI payment " +
            "QR code using the glasses camera. Use when the user asks " +
            "to scan a QR code to pay, or pay with UPI. Returns the " +
            "payee name, amount, and currency when a UPI QR is found. " +
            "After announcing the details to the user, ASK for " +
            "confirmation before calling open_payment_app.",
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
        if (!glasses.ensureCameraPermission()) {
            return upiError("Camera permission denied.")
        }
        val jpeg = camera.captureFrame()
            ?: return upiError("Couldn't capture from the glasses camera.")

        val bitmap = android.graphics.BitmapFactory.decodeByteArray(
            jpeg, 0, jpeg.size,
        ) ?: return upiError("Couldn't decode the camera frame.")

        val image = com.google.mlkit.vision.common.InputImage.fromBitmap(
            bitmap, 0,
        )
        val scanner = BarcodeScanning.getClient()
        return try {
            val barcodes = runCatching {
                scanner.process(image).await()
            }.getOrElse {
                return upiError("QR scan failed.")
            }

            // Find the first QR code containing a UPI payment URL.
            var found: UpiPaymentData? = null
            var sawNonUpiQr = false
            for (code in barcodes) {
                if (code.format != Barcode.FORMAT_QR_CODE) continue
                val raw = code.rawValue ?: continue
                val upi = parseUpiUrl(raw)
                if (upi != null) {
                    found = upi
                    break
                } else {
                    sawNonUpiQr = true
                }
            }

            if (found == null) {
                return if (sawNonUpiQr) {
                    upiError(
                        "Found QR code(s) but none is a UPI payment code.",
                    )
                } else {
                    upiError("No QR code found. Ask the user to aim the " +
                        "glasses at the payment QR code and try again.")
                }
            }

            // Remember the URL for open_payment_app. The raw URL never
            // goes back to the model.
            lastScannedUpi.upiUrl = found.rawUrl

            Log.i(
                "UpiPayTools",
                "UPI QR scanned: payee=${found.payeeName ?: found.payeeAddress}, " +
                    "amount=${found.amount ?: "not set"}",
            )

            buildJsonObject {
                put("status", "ok")
                put("type", "upi")
                put("payee_address", found.payeeAddress)
                found.payeeName?.let { put("payee_name", it) }
                found.amount?.let { put("amount", it) }
                put("currency", found.currency ?: "INR")
                found.note?.let { put("note", it) }
            }
        } finally {
            scanner.close()
        }
    }
}

/**
 * `open_payment_app` — opens the user's UPI payment app with the most
 * recently scanned QR code's payment details pre-filled.
 *
 * Takes no parameters: it only opens the URL produced by the last
 * [ScanUpiQrTool] call, so the model cannot redirect the payment.
 * The user reviews the payee and amount and confirms inside their own
 * payment app — Veyra never handles money.
 *
 * Gated behind the "UPI Payments" AI Settings toggle.
 */
class OpenPaymentAppTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lastScannedUpi: LastScannedUpi,
) : AgentTool {

    override val name = "open_payment_app"

    override val gate = ToolGate.UPI_PAY

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Opens the user's UPI payment app with the " +
            "previously scanned QR code's payment details pre-filled. " +
            "Call ONLY after scan_upi_qr succeeded AND the user confirmed " +
            "they want to proceed with the payment. The user reviews and " +
            "confirms the payment inside their own payment app.",
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
        val upiUrl = lastScannedUpi.upiUrl
            ?: return upiError(
                "No UPI QR code has been scanned yet. " +
                    "Call scan_upi_qr first.",
            )

        return try {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(upiUrl),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            Log.i("UpiPayTools", "Opened payment app for scanned UPI QR")

            // Clear so a stale URL can't be re-opened later.
            lastScannedUpi.upiUrl = null

            buildJsonObject {
                put("status", "ok")
                put(
                    "message",
                    "Payment app opened. The user should verify the " +
                        "payee and amount there before confirming.",
                )
            }
        } catch (e: Exception) {
            Log.e("UpiPayTools", "Failed to open payment app", e)
            upiError(
                "Couldn't open a payment app. Is a UPI app installed?",
            )
        }
    }
}
