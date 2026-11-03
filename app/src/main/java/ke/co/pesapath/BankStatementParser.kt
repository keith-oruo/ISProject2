package ke.co.pesapath

import java.math.BigDecimal
import java.security.MessageDigest
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class StatementDirection {
    DEBIT,
    CREDIT
}

internal data class BankStatementEntry(
    val sourceId: String,
    val date: Long,
    val description: String,
    val amountCents: Long,
    val direction: StatementDirection
)

internal object BankStatementParser {
    private const val MAX_INPUT_LENGTH = 2_000_000
    private const val MAX_ENTRIES = 300

    private val dateFormats = listOf(
        "dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy", "d-M-yyyy",
        "yyyy-MM-dd", "dd MMM yyyy", "d MMM yyyy", "dd-MMM-yyyy", "d-MMM-yyyy", "MMM dd, yyyy",
        "dd/MM/yyyy HH:mm", "d/M/yyyy HH:mm", "dd-MM-yyyy HH:mm", "yyyy-MM-dd'T'HH:mm:ss",
        "dd/MM/yyyy hh:mm a", "d/M/yyyy hh:mm a", "dd-MM-yyyy hh:mm a",
        "dd/MM/yy", "d/M/yy", "dd-MM-yy", "d-M-yy"
    )
    private val textDate = Regex("""^\s*(\d{1,2}[/-]\d{1,2}[/-]\d{2,4}|\d{4}-\d{1,2}-\d{1,2}|\d{1,2}\s+[A-Za-z]{3,9}\s+\d{4})\b""")
    private val moneyToken = Regex("""(?i)(?:KES|KSHS?\.?)\s*[\(]?-?[\d,]+(?:\.\d{1,2})?[\)]?|\(?-?[\d,]+\.\d{1,2}\)?""")
    private val debitWords = Regex("""(?i)\b(?:debit|withdraw(?:al|n)?|paid\s+out|dr)\b""")
    private val creditWords = Regex("""(?i)\b(?:credit|deposit|paid\s+in|cr)\b""")

    fun parseCsv(csv: String): List<BankStatementEntry> {
        require(csv.length <= MAX_INPUT_LENGTH) { "The CSV file is too large to import." }
        val firstLine = csv.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val delimiter = when {
            firstLine.count { it == ';' } > firstLine.count { it == ',' } -> ';'
            firstLine.count { it == '\t' } > firstLine.count { it == ',' } -> '\t'
            else -> ','
        }
        val rows = parseCsvRows(csv, delimiter)
        if (rows.isEmpty()) return emptyList()

        val headerIndex = rows.indexOfFirst { row ->
            val headers = row.map(::normalizeHeader)
            headers.any(::isDateHeader) &&
                headers.any(::isDescriptionHeader) &&
                (headers.any(::isDebitHeader) || headers.any(::isAmountHeader))
        }
        require(headerIndex >= 0) {
            "Could not find statement columns. Include a date, description, and debit/credit or amount column."
        }

        val headers = rows[headerIndex].map(::normalizeHeader)
        val dateIndex = headers.indexOfFirst(::isDateHeader)
        val descriptionIndex = headers.indexOfFirst(::isDescriptionHeader)
        val debitIndex = headers.indexOfFirst(::isDebitHeader)
        val creditIndex = headers.indexOfFirst(::isCreditHeader)
        val amountIndex = headers.indexOfFirst(::isAmountHeader)
        val directionIndex = headers.indexOfFirst(::isDirectionHeader)
        val result = mutableListOf<BankStatementEntry>()
        val occurrences = mutableMapOf<String, Int>()

        rows.drop(headerIndex + 1).forEach { row ->
            if (result.size >= MAX_ENTRIES) return@forEach
            val date = row.getOrNull(dateIndex)?.let(::parseDate) ?: return@forEach
            val description = row.getOrNull(descriptionIndex)?.trim()
                ?.replace(Regex("""\s+"""), " ")
                ?.take(160)
                .orEmpty()
            if (description.isBlank()) return@forEach

            if (debitIndex >= 0 || creditIndex >= 0) {
                val debit = row.getOrNull(debitIndex)?.let(::parseAmountCents)?.takeIf { it != 0L }
                val credit = row.getOrNull(creditIndex)?.let(::parseAmountCents)?.takeIf { it != 0L }
                if (debit != null && credit == null) {
                    result += entry(date, description, kotlin.math.abs(debit), StatementDirection.DEBIT, occurrences)
                } else if (credit != null && debit == null) {
                    result += entry(date, description, kotlin.math.abs(credit), StatementDirection.CREDIT, occurrences)
                }
                return@forEach
            }

            val rawAmount = row.getOrNull(amountIndex).orEmpty().trim()
            val signedAmount = parseAmountCents(rawAmount) ?: return@forEach
            if (signedAmount == 0L) return@forEach
            val directionText = row.getOrNull(directionIndex).orEmpty()
            val direction = when {
                debitWords.containsMatchIn(directionText) || signedAmount < 0L -> StatementDirection.DEBIT
                creditWords.containsMatchIn(directionText) -> StatementDirection.CREDIT
                else -> return@forEach
            }
            result += entry(date, description, kotlin.math.abs(signedAmount), direction, occurrences)
        }
        return result
    }

    fun parseRecognizedText(text: String): List<BankStatementEntry> {
        require(text.length <= MAX_INPUT_LENGTH) { "The recognized statement text is too large." }
        val result = mutableListOf<BankStatementEntry>()
        val occurrences = mutableMapOf<String, Int>()
        text.lineSequence().take(5_000).forEach { line ->
            if (result.size >= MAX_ENTRIES) return@forEach
            val dateMatch = textDate.find(line) ?: return@forEach
            val date = parseDate(dateMatch.groupValues[1]) ?: return@forEach
            val remainder = line.removeRange(dateMatch.range).trim()
            val amounts = moneyToken.findAll(remainder).toList()
            if (amounts.isEmpty()) return@forEach

            val debitMatch = debitWords.find(remainder)
            val creditMatch = creditWords.find(remainder)
            val amountMatch = when {
                debitMatch != null -> amounts.lastOrNull { it.range.first < debitMatch.range.first }
                    ?: amounts.firstOrNull()
                creditMatch != null -> amounts.lastOrNull { it.range.first < creditMatch.range.first }
                    ?: amounts.firstOrNull()
                else -> amounts.firstOrNull { it.value.trim().startsWith("-") || it.value.trim().startsWith("(") }
            } ?: return@forEach

            val amount = parseAmountCents(amountMatch.value)?.let { kotlin.math.abs(it) } ?: return@forEach
            if (amount == 0L) return@forEach
            val direction = when {
                debitMatch != null || amountMatch.value.trim().startsWith("-") ||
                    amountMatch.value.trim().startsWith("(") -> StatementDirection.DEBIT
                creditMatch != null -> StatementDirection.CREDIT
                else -> return@forEach
            }
            val description = remainder
                .removeRange(amountMatch.range)
                .replace(debitWords, " ")
                .replace(creditWords, " ")
                .replace(moneyToken, " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .take(160)
            if (description.isBlank()) return@forEach
            result += entry(date, description, amount, direction, occurrences)
        }
        return result
    }

    private fun entry(
        date: Long,
        description: String,
        amount: Long,
        direction: StatementDirection,
        occurrences: MutableMap<String, Int>
    ): BankStatementEntry {
        val canonical = "${date / 86_400_000L}|${description.lowercase(Locale.ROOT)}|$amount|$direction"
        val occurrence = occurrences.getOrDefault(canonical, 0)
        occurrences[canonical] = occurrence + 1
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$canonical|$occurrence".toByteArray(Charsets.UTF_8))
        val id = bytes.joinToString("") { "%02x".format(it) }
        return BankStatementEntry(
            sourceId = "statement_$id",
            date = date,
            description = description,
            amountCents = amount,
            direction = direction
        )
    }

    private fun parseAmountCents(raw: String): Long? = try {
        val cleaned = raw.trim()
            .replace(Regex("""(?i)KES|KSHS?\.?"""), "")
            .replace(",", "")
            .replace(" ", "")
        val negative = cleaned.startsWith("-") || cleaned.startsWith("(") && cleaned.endsWith(")")
        val number = cleaned.removePrefix("-").removePrefix("(").removeSuffix(")")
        val cents = BigDecimal(number).movePointRight(2).longValueExact()
        if (negative) -cents else cents
    } catch (_: NumberFormatException) {
        null
    } catch (_: ArithmeticException) {
        null
    }

    private fun parseDate(raw: String): Long? {
        val value = raw.trim()
        dateFormats.forEach { pattern ->
            val formatter = SimpleDateFormat(pattern, Locale.ENGLISH).apply { isLenient = false }
            val position = ParsePosition(0)
            val date: Date = formatter.parse(value, position) ?: return@forEach
            if (position.index == value.length) return date.time
        }
        return null
    }

    private fun parseCsvRows(csv: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < csv.length) {
            val char = csv[index]
            when {
                char == '"' && quoted && index + 1 < csv.length && csv[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == delimiter && !quoted -> {
                    row += field.toString()
                    field.clear()
                }
                (char == '\n' || char == '\r') && !quoted -> {
                    row += field.toString()
                    field.clear()
                    if (row.any { it.isNotBlank() }) rows += row
                    row = mutableListOf()
                    if (char == '\r' && index + 1 < csv.length && csv[index + 1] == '\n') index++
                }
                else -> field.append(char)
            }
            index++
        }
        row += field.toString()
        if (row.any { it.isNotBlank() }) rows += row
        return rows
    }

    private fun normalizeHeader(value: String): String =
        value.removePrefix("\uFEFF").lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9]"""), "")

    private fun isDateHeader(header: String): Boolean =
        header in setOf("date", "datetime", "transactiondate", "transactiondatetime", "txndate", "postingdate", "valuedate")

    private fun isDescriptionHeader(header: String): Boolean =
        header in setOf(
            "description", "details", "particulars", "narration", "narrative", "payee",
            "merchant", "remarks", "transaction", "transactiondetails"
        )

    private fun isDebitHeader(header: String): Boolean =
        header.startsWith("debit") || header.startsWith("withdraw") || header == "paidout"

    private fun isCreditHeader(header: String): Boolean =
        header.startsWith("credit") || header.startsWith("deposit") || header == "paidin"

    private fun isAmountHeader(header: String): Boolean =
        header == "amount" || header.startsWith("amount") || header == "transactionamount" || header == "value"

    private fun isDirectionHeader(header: String): Boolean =
        header in setOf("type", "direction", "drcr", "transactiontype")
}
