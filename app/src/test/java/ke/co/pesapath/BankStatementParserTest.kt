package ke.co.pesapath

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BankStatementParserTest {
    @Test
    fun parsesDebitAndCreditColumnsAndIgnoresOpeningBalanceRows() {
        val entries = BankStatementParser.parseCsv(
            """
                Account statement
                Date,Description,Debit,Credit,Balance
                01/04/2025,"Supermarket, Nairobi","1,250.00",,"8,750.00"
                02/04/2025,Salary,,"50,000.00","58,750.00"
                Opening Balance,,,,
            """.trimIndent()
        )

        assertEquals(2, entries.size)
        assertEquals(StatementDirection.DEBIT, entries[0].direction)
        assertEquals(125_000L, entries[0].amountCents)
        assertEquals("Supermarket, Nairobi", entries[0].description)
        assertEquals(StatementDirection.CREDIT, entries[1].direction)
        assertEquals(5_000_000L, entries[1].amountCents)
    }

    @Test
    fun parsesSignedAmountAndDirectionColumns() {
        val entries = BankStatementParser.parseCsv(
            """
                Date,Details,Amount,Type
                2025-04-03,Restaurant,-850.50,DR
                2025-04-04,Refund,500.00,CR
                2025-04-05,Unknown,100.00,
            """.trimIndent()
        )

        assertEquals(2, entries.size)
        assertEquals(85_050L, entries[0].amountCents)
        assertEquals(StatementDirection.DEBIT, entries[0].direction)
        assertEquals(StatementDirection.CREDIT, entries[1].direction)
    }

    @Test
    fun parsesSemicolonDelimitedBankExports() {
        val entries = BankStatementParser.parseCsv(
            """
                Date;Particulars;Withdrawals;Deposits
                06/04/2025;Fuel;2,000.00;
            """.trimIndent()
        )

        assertEquals(1, entries.size)
        assertEquals(200_000L, entries.single().amountCents)
        assertEquals(StatementDirection.DEBIT, entries.single().direction)
    }

    @Test
    fun parsesRecognizedStatementLinesAndKeepsCreditDirection() {
        val entries = BankStatementParser.parseRecognizedText(
            """
                03/04/2025 MARKET PLACE KES 1,250.00 DR 8,000.00
                04/04/2025 SALARY KES 50,000.00 CR 58,000.00
            """.trimIndent()
        )

        assertEquals(2, entries.size)
        assertEquals(StatementDirection.DEBIT, entries[0].direction)
        assertEquals(125_000L, entries[0].amountCents)
        assertTrue(entries[0].description.contains("MARKET PLACE"))
        assertEquals(StatementDirection.CREDIT, entries[1].direction)
    }

    @Test
    fun createsStableUniqueIdsForRepeatedStatementRows() {
        val csv = """
            Date,Description,Debit,Credit
            01/04/2025,Transport,100.00,
            01/04/2025,Transport,100.00,
        """.trimIndent()

        val firstImport = BankStatementParser.parseCsv(csv)
        val secondImport = BankStatementParser.parseCsv(csv)
        assertEquals(firstImport.map { it.sourceId }, secondImport.map { it.sourceId })
        assertNotEquals(firstImport[0].sourceId, firstImport[1].sourceId)
    }

    @Test
    fun parsesDateAsCalendarDay() {
        val entry = BankStatementParser.parseCsv(
            "Date,Description,Debit\n15/04/2025,Transport,100.00"
        ).single()
        val date = Calendar.getInstance().apply { timeInMillis = entry.date }
        assertEquals(2025, date.get(Calendar.YEAR))
        assertEquals(Calendar.APRIL, date.get(Calendar.MONTH))
        assertEquals(15, date.get(Calendar.DAY_OF_MONTH))
    }
}
