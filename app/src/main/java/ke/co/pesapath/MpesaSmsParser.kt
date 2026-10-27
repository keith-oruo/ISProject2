package ke.co.pesapath

import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Calendar

data class ImportedMpesaMessage(
    val sourceId: String,
    val amountCents: Long,
    val date: Long,
    val note: String
)

object MpesaSmsParser {
    private val amountPattern = Regex("""(?i)\bkshs?\s*(\d+(?:,\d{3})*(?:\.\d{1,2})?)(?![\d,]|\.\d)""")
    private val balancePattern = Regex(
        """(?i)\bbalance\b[^\r\n]{0,30}?\bkshs?\s*(\d+(?:,\d{3})*(?:\.\d{1,2})?)(?![\d,]|\.\d)"""
    )
    private val outgoingPattern = Regex(
        """(?i)\b(?:sent to|paid to|paid for|paid bill for|buy goods at|bought at)\s+(.+?)(?:\s+on\s+\d{1,2}[/-]\d{1,2}[/-]\d{2,4}|\s+at\s+\d{1,2}:\d{2}|[.\n]|$)"""
    )
    private val datePattern = Regex(
        """(?i)\bon\s+(\d{1,2})[/-](\d{1,2})[/-](\d{2,4})(?:\s+at\s+(\d{1,2}):(\d{2})\s*(AM|PM)?)?"""
    )

    fun parseBalanceCents(message: String): Long? {
        val text = message.trim()
        if (text.isEmpty() || text.length > 4_000) return null
        if (listOf("confirmed", "sent to", "paid to", "paid for", "received", "reversed", "reversal", "failed")
                .any { text.contains(it, ignoreCase = true) }) return null
        val amountText = balancePattern.find(text)?.groupValues?.getOrNull(1) ?: return null
        return try {
            BigDecimal(amountText.replace(",", "")).movePointRight(2).longValueExact()
        } catch (_: NumberFormatException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }

    fun parse(message: String): ImportedMpesaMessage? {
        val text = message.trim()
        if (text.isEmpty() || text.length > 4_000) return null
        if (!text.contains("confirmed", ignoreCase = true)) return null
        if (listOf("received", "reversed", "reversal", "failed", "cancelled")
                .any { text.contains(it, ignoreCase = true) }) return null

        val amountText = amountPattern.find(text)?.groupValues?.getOrNull(1) ?: return null
        val amountCents = try {
            BigDecimal(amountText.replace(",", "")).movePointRight(2).longValueExact()
        } catch (_: NumberFormatException) {
            return null
        } catch (_: ArithmeticException) {
            return null
        }
        if (amountCents <= 0L) return null

        val payee = outgoingPattern.find(text)?.groupValues?.getOrNull(1)?.trim()
            ?.replace(Regex("""\s+\d{1,4}\*{2,}\d{2,}"""), "")
            ?.replace(Regex("""(?:\+?254|0)[17]\d{8}"""), "M-Pesa contact")
            ?.take(80)
            ?.trim()
            .orEmpty()
        if (payee.isBlank()) return null

        val date = parseDate(text) ?: return null
        return ImportedMpesaMessage(
            sourceId = sourceId(text),
            amountCents = amountCents,
            date = date,
            note = "M-Pesa to $payee"
        )
    }

    private fun sourceId(message: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("sms:$message".toByteArray(Charsets.UTF_8))
        return "mpesa_" + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun parseDate(message: String): Long? {
        val match = datePattern.find(message) ?: return System.currentTimeMillis()
        val parts = match.groupValues
        val day = parts[1].toIntOrNull() ?: return null
        val month = parts[2].toIntOrNull()?.minus(1) ?: return null
        var year = parts[3].toIntOrNull() ?: return null
        if (year < 100) year += if (year < 70) 2000 else 1900
        var hour = parts[4].toIntOrNull() ?: 12
        val minute = parts[5].toIntOrNull() ?: 0
        when (parts[6].uppercase()) {
            "AM" -> if (hour == 12) hour = 0
            "PM" -> if (hour in 1..11) hour += 12
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return try {
            Calendar.getInstance().apply {
                isLenient = false
                clear()
                set(year, month, day, hour, minute, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
