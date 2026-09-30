package com.geno.veyra.tools

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.geno.veyra.gemini.protocol.FunctionDeclaration
import com.geno.veyra.glasses.GlassesCameraSource
import com.geno.veyra.glasses.GlassesManager
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the most recently scanned payment URL.
 *
 * [OpenPaymentAppTool] only ever opens the URL produced by
 * [ScanPayQrTool] — it takes no URL parameter, so the model cannot
 * inject an arbitrary payment destination.
 */
@Singleton
class LastScannedPayment @Inject constructor() {
    @Volatile
    var paymentUrl: String? = null
}

/** Which payment app a scanned QR code belongs to. */
enum class PaymentAppType(val appName: String) {
    UPI("UPI"),
    VENMO("Venmo"),
    CASH_APP("Cash App"),
}

/**
 * Parsed payment details from a scanned QR code.
 *
 * UPI QRs carry the full payment (payee, amount); Venmo and Cash App
 * QRs only identify the recipient — the amount is entered by the
 * payer inside the app.
 */
data class PaymentQrData(
    val type: PaymentAppType,
    /** Human-readable recipient: payee name, Venmo username, $cashtag. */
    val recipient: String?,
    /** UPI only. */
    val amount: String?,
    /** UPI only. */
    val currency: String?,
    /** UPI only. */
    val note: String?,
    /** The raw URL. Stored for [OpenPaymentAppTool]; never sent to the model. */
    val rawUrl: String,
)

/**
 * Parses a QR code value into payment details.
 * Returns null when the value is not a recognized payment QR.
 */
fun parsePaymentQr(rawValue: String): PaymentQrData? {
    // UPI: upi://pay?pa=payee@upi&pn=Name&am=100.00&cu=INR&tn=Note
    if (rawValue.startsWith("upi://pay", ignoreCase = true)) {
        return try {
            val uri = Uri.parse(rawValue)
            val payeeAddress = uri.getQueryParameter("pa") ?: return null
            PaymentQrData(
                type = PaymentAppType.UPI,
                recipient = uri.getQueryParameter("pn") ?: payeeAddress,
                amount = uri.getQueryParameter("am"),
                currency = uri.getQueryParameter("cu") ?: "INR",
                note = uri.getQueryParameter("tn"),
                rawUrl = rawValue,
            )
        } catch (e: Exception) {
            Log.w("LookPay", "Failed to parse UPI URL", e)
            null
        }
    }

    // Venmo: https://venmo.com/code?user_id=... or https://venmo.com/username
    if (rawValue.contains("venmo.com", ignoreCase = true)) {
        return try {
            val uri = Uri.parse(rawValue)
            val username = uri.pathSegments.firstOrNull()
                ?.takeIf { it != "code" }
                ?: uri.getQueryParameter("user_id")
                ?: "Venmo user"
            PaymentQrData(
                type = PaymentAppType.VENMO,
                recipient = username,
                amount = null,
                currency = null,
                note = null,
                rawUrl = rawValue,
            )
        } catch (e: Exception) {
            Log.w("LookPay", "Failed to parse Venmo URL", e)
            null
        }
    }

    // Cash App: https://cash.app/$cashtag
    if (rawValue.contains("cash.app", ignoreCase = true)) {
        return try {
            val uri = Uri.parse(rawValue)
            val cashtag = uri.pathSegments.firstOrNull()
                ?.takeIf { it.startsWith("$") }
                ?: "Cash App user"
            PaymentQrData(
                type = PaymentAppType.CASH_APP,
                recipient = cashtag,
                amount = null,
                currency = null,
                note = null,
                rawUrl = rawValue,
            )
        } catch (e: Exception) {
            Log.w("LookPay", "Failed to parse Cash App URL", e)
            null
        }
    }

    return null
}

private fun payError(message: String): JsonObject =
    buildJsonObject {
        put("status", "error")
        put("message", message)
    }

/**
 * `scan_pay_qr` — "Look and Pay": captures the user's current view from
 * the glasses camera and looks for a payment QR code, on-device via
 * ML Kit.
 *
 * Supports UPI (`upi://pay`), Venmo, and Cash App QR codes. Returns
 * the payment app, recipient, and amount when available. The raw URL
 * is kept for [OpenPaymentAppTool]; it is NOT returned to the model.
 *
 * Gated behind the "Look and Pay" AI Settings toggle.
 */
class ScanPayQrTool @Inject constructor(
    private val glasses: GlassesManager,
    private val camera: GlassesCameraSource,
    private val lastScannedPayment: LastScannedPayment,
) : AgentTool {

    override val name = "scan_pay_qr"

    override val gate = ToolGate.LOOK_PAY

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Scans the user's current view for a payment QR " +
            "code using the glasses camera (UPI, Venmo, or Cash App). " +
            "Use when the user asks to scan a QR code to pay. Returns " +
            "the payment app, recipient, and amount when available. " +
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
            return payError("Camera permission denied.")
        }
        val jpeg = camera.captureFrame()
            ?: return payError("Couldn't capture from the glasses camera.")

        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: return payError("Couldn't decode the camera frame.")

        val image = InputImage.fromBitmap(bitmap, 0)
        val scanner = BarcodeScanning.getClient()
        return try {
            val barcodes = runCatching {
                scanner.process(image).await()
            }.getOrElse {
                return payError("QR scan failed.")
            }

            // Find the first QR code containing a recognized payment URL.
            var found: PaymentQrData? = null
            var sawNonPaymentQr = false
            for (code in barcodes) {
                if (code.format != Barcode.FORMAT_QR_CODE) continue
                val raw = code.rawValue ?: continue
                val payment = parsePaymentQr(raw)
                if (payment != null) {
                    found = payment
                    break
                } else {
                    sawNonPaymentQr = true
                }
            }

            if (found == null) {
                return if (sawNonPaymentQr) {
                    payError(
                        "Found QR code(s) but none is a recognized " +
                            "payment code (UPI, Venmo, or Cash App).",
                    )
                } else {
                    payError("No QR code found. Ask the user to aim the " +
                        "glasses at the payment QR code and try again.")
                }
            }

            // Remember the URL for open_payment_app. The raw URL never
            // goes back to the model.
            lastScannedPayment.paymentUrl = found.rawUrl

            Log.i(
                "LookPay",
                "Payment QR scanned: app=${found.type.appName}, " +
                    "recipient=${found.recipient}, " +
                    "amount=${found.amount ?: "not set"}",
            )

            buildJsonObject {
                put("status", "ok")
                put("payment_app", found.type.appName)
                found.recipient?.let { put("recipient", it) }
                found.amount?.let { put("amount", it) }
                found.currency?.let { put("currency", it) }
                found.note?.let { put("note", it) }
            }
        } finally {
            scanner.close()
        }
    }
}

/**
 * `open_payment_app` — opens the user's payment app with the most
 * recently scanned QR code's payment details.
 *
 * Takes no parameters: it only opens the URL produced by the last
 * [ScanPayQrTool] call, so the model cannot redirect the payment.
 * The user reviews the details and confirms inside their own payment
 * app — Veyra never handles money.
 *
 * Gated behind the "Look and Pay" AI Settings toggle.
 */
class OpenPaymentAppTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lastScannedPayment: LastScannedPayment,
) : AgentTool {

    override val name = "open_payment_app"

    override val gate = ToolGate.LOOK_PAY

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Opens the user's payment app with the previously " +
            "scanned QR code's payment details. Call ONLY after " +
            "scan_pay_qr succeeded AND the user confirmed they want to " +
            "proceed with the payment. The user reviews and confirms " +
            "the payment inside their own payment app.",
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
        val paymentUrl = lastScannedPayment.paymentUrl
            ?: return payError(
                "No payment QR code has been scanned yet. " +
                    "Call scan_pay_qr first.",
            )

        return try {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(paymentUrl),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            Log.i("LookPay", "Opened payment app for scanned QR")

            // Clear so a stale URL can't be re-opened later.
            lastScannedPayment.paymentUrl = null

            buildJsonObject {
                put("status", "ok")
                put(
                    "message",
                    "Payment app opened. The user should verify the " +
                        "details there before confirming.",
                )
            }
        } catch (e: Exception) {
            Log.e("LookPay", "Failed to open payment app", e)
            payError(
                "Couldn't open a payment app. Is one installed?",
            )
        }
    }
}
