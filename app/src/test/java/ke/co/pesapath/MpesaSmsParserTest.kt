package ke.co.pesapath

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpesaSmsParserTest {
    @Test
    fun parsesConfirmedOutgoingPaymentAndMasksPhoneNumber() {
        val imported = MpesaSmsParser.parse(
            "QK123ABC Confirmed. Ksh1,250.00 sent to Jane Doe 0712345678 on 15/04/2025 at 10:30 AM."
        )

        assertNotNull(imported)
        assertEquals(125_000L, imported?.amountCents)
        assertTrue(imported?.note?.contains("Jane Doe") == true)
        assertFalse(imported?.note?.contains("0712345678") == true)
        val date = Calendar.getInstance().apply { timeInMillis = imported!!.date }
        assertEquals(2025, date.get(Calendar.YEAR))
        assertEquals(Calendar.APRIL, date.get(Calendar.MONTH))
        assertEquals(15, date.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun rejectsIncomingReversedFailedAndUnsupportedMessages() {
        val rejectedMessages = listOf(
            "Confirmed. You have received Ksh1,000.00 from Jane Doe.",
            "Confirmed. Ksh1,000.00 sent to Jane Doe. Transaction reversed.",
            "Ksh1,000.00 sent to Jane Doe failed.",
            "Confirmed. Ksh1,000.00 paid."
        )

        rejectedMessages.forEach { assertNull(MpesaSmsParser.parse(it)) }
    }

    @Test
    fun rejectsMalformedOrOutOfRangeAmountsAndDates() {
        val rejectedMessages = listOf(
            "Confirmed. Ksh1,23.45 sent to Jane Doe.",
            "Confirmed. Ksh1.234 sent to Jane Doe.",
            "Confirmed. Ksh0.00 sent to Jane Doe.",
            "Confirmed. Ksh1.00 sent to Jane Doe on 31/02/2025."
        )

        rejectedMessages.forEach { assertNull(MpesaSmsParser.parse(it)) }
    }

    @Test
    fun usesStableImportIdForTheSameMessage() {
        val message = "Confirmed. Ksh500.00 paid to Jane Doe on 15/04/2025."

        assertEquals(
            MpesaSmsParser.parse(message)?.sourceId,
            MpesaSmsParser.parse(message)?.sourceId
        )
    }

    @Test
    fun parsesMpesBalanceInquiryWithoutAcceptingTransactionBalance() {
        assertEquals(
            123_456L,
            MpesaSmsParser.parseBalanceCents("Your M-PESA account balance is Ksh 1,234.56.")
        )
        assertEquals(0L, MpesaSmsParser.parseBalanceCents("M-PESA balance: Ksh0.00"))
        assertNull(
            MpesaSmsParser.parseBalanceCents(
                "Confirmed. Ksh500.00 sent to Jane Doe. Your new balance is Ksh1,000.00."
            )
        )
    }

    @Test
    fun parsesPaymentTimeForBalanceReconciliation() {
        val imported = MpesaSmsParser.parse(
            "QK123ABC Confirmed. Ksh500.00 sent to Jane Doe on 15/04/2025 at 3:45 PM."
        )

        assertNotNull(imported)
        val date = Calendar.getInstance().apply { timeInMillis = imported!!.date }
        assertEquals(15, date.get(Calendar.DAY_OF_MONTH))
        assertEquals(15, date.get(Calendar.HOUR_OF_DAY))
        assertEquals(45, date.get(Calendar.MINUTE))
    }
}
