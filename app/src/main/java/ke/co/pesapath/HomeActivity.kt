package ke.co.pesapath

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QueryDocumentSnapshot
import java.math.BigDecimal
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class HomeActivity : AppCompatActivity() {
    private val categories = listOf("Food", "Transport", "Rent", "Shopping", "Bills", "Other")
    private val transactions = mutableListOf<QueryDocumentSnapshot>()
    private val goals = mutableListOf<QueryDocumentSnapshot>()
    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.UK)
    private val currency = NumberFormat.getNumberInstance(Locale.UK).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }
    private lateinit var transactionStore: CollectionReference
    private lateinit var goalStore: CollectionReference
    private lateinit var content: LinearLayout
    private lateinit var heading: TextView
    private var transactionListener: ListenerRegistration? = null
    private var goalListener: ListenerRegistration? = null
    private var page = "Home"
    private var errorMessage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_home)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.homeRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        content = findViewById(R.id.content)
        heading = findViewById(R.id.pageTitle)
        val userStore = FirebaseFirestore.getInstance().collection("users").document(user.uid)
        transactionStore = userStore.collection("transactions")
        goalStore = userStore.collection("goals")

        listOf(R.id.homeTab to "Home", R.id.transactionsTab to "Transactions",
            R.id.chatTab to "Chat", R.id.goalsTab to "Goals", R.id.profileTab to "Profile")
            .forEach { (id, destination) ->
                findViewById<Button>(id).setOnClickListener { show(destination) }
            }
        transactionListener = transactionStore.addSnapshotListener { snapshot, error ->
            if (error != null) {
                errorMessage = "Could not load transactions: ${error.localizedMessage}"
            } else if (snapshot != null) {
                transactions.clear()
                transactions.addAll(snapshot.documents.filterIsInstance<QueryDocumentSnapshot>())
                errorMessage = null
            }
            if (page == "Home" || page == "Transactions") show(page)
        }
        goalListener = goalStore.addSnapshotListener { snapshot, error ->
            if (error != null) errorMessage = "Could not load goals: ${error.localizedMessage}"
            else if (snapshot != null) {
                goals.clear()
                goals.addAll(snapshot.documents.filterIsInstance<QueryDocumentSnapshot>())
            }
            if (page == "Goals") show(page)
        }
        show("Home")
    }

    override fun onDestroy() {
        transactionListener?.remove()
        goalListener?.remove()
        super.onDestroy()
    }

    private fun show(destination: String) {
        page = destination
        content.removeAllViews()
        heading.text = when (destination) {
            "Home" -> "Dashboard"
            "Transactions" -> "Transactions"
            "Goals" -> "Savings Goals"
            "Chat" -> "Financial Assistant"
            else -> "Profile"
        }
        errorMessage?.let { text(it, 14f).setTextColor(0xFFAA3333.toInt()) }
        when (destination) {
            "Home" -> dashboard()
            "Transactions" -> transactionList()
            "Goals" -> goalList()
            "Chat" -> text("The financial assistant is planned for the next phase.", 16f)
            "Profile" -> profile()
        }
    }

    private fun dashboard() {
        val user = FirebaseAuth.getInstance().currentUser
        text("Hi, ${user?.displayName ?: user?.email?.substringBefore('@') ?: "there"}", 19f, true)
        val month = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val nextMonth = Calendar.getInstance().apply {
            timeInMillis = month
            add(Calendar.MONTH, 1)
        }.timeInMillis
        val monthly = transactions.filter { (it.getLong("date") ?: 0L) in month until nextMonth }
        val total = monthly.sumOf { it.getLong("amountCents") ?: 0L }
        text("This Month's Spending", 14f)
        text(money(total), 28f, true)
        text("Spending by category", 17f, true)
        val breakdown = categories.map { category ->
            category to monthly.filter { it.getString("category") == category }
                .sumOf { it.getLong("amountCents") ?: 0L }
        }
        if (total == 0L) text("No transactions this month yet.", 14f)
        breakdown.filter { it.second > 0L }.forEach { (category, amount) ->
            text("$category  ${money(amount)}", 14f)
            content.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = (amount.toDouble() * 100 / total).toInt().coerceIn(0, 100)
            })
        }
        button("+ Add Transaction") { transactionForm(null) }
        button("Chat with Assistant") { show("Chat") }
        text("Recent Transactions", 17f, true)
        val recent = transactions.sortedByDescending { it.getLong("date") ?: 0L }.take(3)
        if (recent.isEmpty()) text("No transactions yet.", 14f)
        recent.forEach { text("${it.getString("category") ?: "Other"}  -  ${money(it.getLong("amountCents") ?: 0L)}", 15f) }
    }

    private fun transactionList() {
        button("+ Add Transaction") { transactionForm(null) }
        val sorted = transactions.sortedByDescending { it.getLong("date") ?: 0L }
        if (sorted.isEmpty()) text("No transactions yet. Add your first purchase.", 15f)
        sorted.forEach { record ->
            text("${record.getString("category") ?: "Other"}  -  ${money(record.getLong("amountCents") ?: 0L)}", 17f, true)
            text("${dateFormat.format(record.getLong("date") ?: 0L)}  ${record.getString("note").orEmpty()}", 13f)
            actions({ transactionForm(record) }, { delete(transactionStore, record.id, "transaction") })
        }
    }

    private fun goalList() {
        button("+ New Goal") { goalForm(null) }
        if (goals.isEmpty()) text("No savings goals yet.", 15f)
        goals.sortedBy { it.getLong("dueDate") ?: Long.MAX_VALUE }.forEach { record ->
            val target = record.getLong("targetCents") ?: 0L
            val saved = record.getLong("savedCents") ?: 0L
            text(record.getString("title") ?: "Savings goal", 18f, true)
            text("Target: ${money(target)}   Saved: ${money(saved)}", 14f)
            text("Due: ${dateFormat.format(record.getLong("dueDate") ?: 0L)}", 14f)
            content.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = if (target > 0) (saved.toDouble() * 100 / target).toInt().coerceIn(0, 100) else 0
            })
            text("${if (target > 0) (saved.toDouble() * 100 / target).toInt().coerceAtLeast(0) else 0}% complete", 13f)
            actions({ goalForm(record) }, { delete(goalStore, record.id, "goal") })
        }
    }

    private fun profile() {
        text("Signed in as ${FirebaseAuth.getInstance().currentUser?.email.orEmpty()}", 16f)
        button("Sign out") {
            FirebaseAuth.getInstance().signOut()
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
        }
    }

    private fun transactionForm(record: QueryDocumentSnapshot?) {
        page = "Transaction form"
        heading.text = if (record == null) "Add Transaction" else "Edit Transaction"
        content.removeAllViews()
        val amount = input("Amount (KES)", record?.getLong("amountCents")?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty(), 8194)
        text("Category", 14f)
        val category = Spinner(this).apply {
            adapter = ArrayAdapter(this@HomeActivity, android.R.layout.simple_spinner_dropdown_item, categories)
            setSelection(categories.indexOf(record?.getString("category")).coerceAtLeast(0))
        }
        content.addView(category)
        var date = record?.getLong("date") ?: System.currentTimeMillis()
        val dateButton = button("Date: ${dateFormat.format(date)}") { }
        dateButton.setOnClickListener {
            val calendar = Calendar.getInstance().apply { timeInMillis = date }
            DatePickerDialog(this, { _, year, month, day ->
                date = Calendar.getInstance().apply {
                    set(year, month, day, 12, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                dateButton.text = "Date: ${dateFormat.format(date)}"
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
        }
        val note = input("Note (optional)", record?.getString("note").orEmpty(), 1)
        lateinit var save: Button
        save = button("SAVE TRANSACTION") {
            val cents = parseCents(amount.text.toString())
            if (cents == null || cents <= 0L) {
                amount.error = "Enter a positive amount with at most 2 decimal places"
                return@button
            }
            val data = mapOf("amountCents" to cents, "category" to category.selectedItem.toString(),
                "date" to date, "note" to note.text.toString().trim())
            saveRecord(transactionStore, record?.id, data, save, "Transactions")
        }
        button("Cancel") { show("Transactions") }
    }

    private fun goalForm(record: QueryDocumentSnapshot?) {
        page = "Goal form"
        heading.text = if (record == null) "New Savings Goal" else "Edit Savings Goal"
        content.removeAllViews()
        val title = input("Goal name", record?.getString("title").orEmpty(), 1)
        val target = input("Target (KES)", record?.getLong("targetCents")?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty(), 8194)
        val saved = input("Saved so far (KES)", record?.getLong("savedCents")?.let { BigDecimal.valueOf(it, 2).toPlainString() } ?: "0", 8194)
        var dueDate = record?.getLong("dueDate") ?: Calendar.getInstance().apply { add(Calendar.MONTH, 1) }.timeInMillis
        val dateButton = button("Due: ${dateFormat.format(dueDate)}") { }
        dateButton.setOnClickListener {
            val calendar = Calendar.getInstance().apply { timeInMillis = dueDate }
            DatePickerDialog(this, { _, year, month, day ->
                dueDate = Calendar.getInstance().apply { set(year, month, day, 12, 0, 0) }.timeInMillis
                dateButton.text = "Due: ${dateFormat.format(dueDate)}"
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
        }
        lateinit var save: Button
        save = button("SAVE GOAL") {
            val targetCents = parseCents(target.text.toString())
            val savedCents = parseCents(saved.text.toString())
            if (title.text.isBlank()) { title.error = "Enter a goal name"; return@button }
            if (targetCents == null || targetCents <= 0) { target.error = "Enter a positive target"; return@button }
            if (savedCents == null || savedCents < 0) { saved.error = "Enter a valid saved amount"; return@button }
            if (dueDate < System.currentTimeMillis() && record == null) {
                dateButton.error = "Choose a future date"
                return@button
            }
            val data = mapOf("title" to title.text.toString().trim(), "targetCents" to targetCents,
                "savedCents" to savedCents, "dueDate" to dueDate)
            saveRecord(goalStore, record?.id, data, save, "Goals")
        }
        button("Cancel") { show("Goals") }
    }

    private fun saveRecord(store: CollectionReference, id: String?, data: Map<String, Any>, save: Button, destination: String) {
        save.isEnabled = false
        val task = if (id == null) store.add(data) else store.document(id).set(data)
        task.addOnCompleteListener(this) { result ->
            save.isEnabled = true
            if (result.isSuccessful) show(destination)
            else AlertDialog.Builder(this).setMessage(result.exception?.localizedMessage ?: "Save failed")
                .setPositiveButton("OK", null).show()
        }
    }

    private fun delete(store: CollectionReference, id: String, label: String) {
        AlertDialog.Builder(this).setMessage("Delete this $label?")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                store.document(id).delete().addOnFailureListener { error ->
                    AlertDialog.Builder(this).setMessage(error.localizedMessage ?: "Delete failed")
                        .setPositiveButton("OK", null).show()
                }
            }.show()
    }

    private fun actions(onEdit: () -> Unit, onDelete: () -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply { text = "Edit"; setOnClickListener { onEdit() } }, LinearLayout.LayoutParams(0, 48.dp, 1f))
        row.addView(Button(this).apply { text = "Delete"; setOnClickListener { onDelete() } }, LinearLayout.LayoutParams(0, 48.dp, 1f))
        content.addView(row)
    }

    private fun text(value: String, size: Float, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(0xFF18302B.toInt())
        if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding(0, 12.dp, 0, 8.dp)
        content.addView(this)
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
        content.addView(this, LinearLayout.LayoutParams(-1, 52.dp).apply { topMargin = 12.dp })
    }

    private fun input(hintText: String, value: String, type: Int): EditText = EditText(this).apply {
        hint = hintText
        setText(value)
        inputType = type
        content.addView(this, LinearLayout.LayoutParams(-1, 58.dp).apply { topMargin = 12.dp })
    }

    private fun parseCents(raw: String): Long? = try {
        BigDecimal(raw.trim()).movePointRight(2).longValueExact()
    } catch (_: Exception) { null }

    private fun money(cents: Long): String = "KES ${currency.format(BigDecimal.valueOf(cents, 2))}"
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}