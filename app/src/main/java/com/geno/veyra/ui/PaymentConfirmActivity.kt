package com.geno.veyra.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.geno.veyra.R
import com.geno.veyra.tools.LastScannedPayment
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The hard confirmation step for Look and Pay. Shows the scanned
 * payment's details and opens the payment app ONLY when the user taps
 * Open here — the assistant (and its tools) can show this prompt but
 * can never open the payment itself.
 *
 * Reads the pending scan from [LastScannedPayment]; a successful open
 * consumes it (single use). Dismissing keeps the scan until it expires
 * (two minutes from the scan).
 */
@AndroidEntryPoint
class PaymentConfirmActivity : Activity() {

    @Inject
    lateinit var lastScannedPayment: LastScannedPayment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val data = lastScannedPayment.data
        val url = lastScannedPayment.paymentUrl
        if (data == null || url == null) {
            finish()
            return
        }

        val message = buildString {
            append(data.type.appName)
            data.recipient?.let {
                append("\n")
                append(getString(R.string.pay_confirm_to, it))
            }
            if (data.amount != null) {
                append("\n")
                append(
                    getString(
                        R.string.pay_confirm_amount,
                        data.amount,
                        data.currency ?: "",
                    ),
                )
            }
            data.note?.let {
                append("\n")
                append(it)
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.pay_confirm_title)
            .setMessage(message)
            .setPositiveButton(R.string.pay_confirm_open) { _, _ ->
                openPaymentApp(url)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun openPaymentApp(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.i(TAG, "Payment app opened from confirmation prompt")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open payment app", e)
        } finally {
            // Single use: the scan is consumed by the open attempt.
            lastScannedPayment.clear()
            finish()
        }
    }

    companion object {
        private const val TAG = "LookPay"
    }
}
