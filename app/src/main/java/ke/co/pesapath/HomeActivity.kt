package ke.co.pesapath

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.appcompat.content.res.AppCompatResources
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QueryDocumentSnapshot
import java.math.BigDecimal
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

class HomeActivity : AppCompatActivity() {
    private val categories = listOf("Food", "Transport", "Rent", "Shopping", "Bills", "Subscriptions", "Other")
    private val subcategories = mapOf(
        "Food" to listOf("Groceries", "Dining", "Coffee", "Takeaway", "Other"),
        "Transport" to listOf("Fuel", "Public transport", "Ride hailing", "Parking", "Other"),
        "Rent" to listOf("Housing", "Maintenance", "Other"),
        "Shopping" to listOf("Clothing", "Home", "Electronics", "Personal care", "Other"),
        "Bills" to listOf("Utilities", "Internet", "Phone", "Other"),
        "Subscriptions" to listOf(
            "Business", "Entertainment", "Music and streaming", "Software", "Education",
            "Health and fitness", "Other"
        ),
        "Other" to listOf("General", "Other")
    )
    private val transactions = mutableListOf<QueryDocumentSnapshot>()
    private val goals = mutableListOf<QueryDocumentSnapshot>()
    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.UK)
    private val currency = NumberFormat.getNumberInstance(Locale.UK).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }
    private lateinit var transactionStore: CollectionReference
    private lateinit var goalStore: CollectionReference
    private lateinit var chatStore: CollectionReference
    private lateinit var balanceStore: DocumentReference
    private lateinit var content: LinearLayout
    private lateinit var heading: TextView
    private lateinit var chatComposer: LinearLayout
    private lateinit var chatInput: EditText
    private lateinit var chatSendButton: Button
    private val chatDocuments = mutableListOf<QueryDocumentSnapshot>()
    private var transactionListener: ListenerRegistration? = null
    private var goalListener: ListenerRegistration? = null
    private var balanceListener: ListenerRegistration? = null
    private var chatListener: ListenerRegistration? = null
    private var chatMessagesContainer: LinearLayout? = null
    private var chatStatusView: TextView? = null
    private var chatService: FinancialChatService? = null
    private var chatHistoryLoaded = false
    private var chatLoadError: String? = null
    private var chatSetupError: String? = null
    private var chatSending = false
    private val chatExecutor = Executors.newSingleThreadExecutor()
    private var page = "Home"
    private var errorMessage: String? = null
    private var balanceSnapshotCents: Long? = null
    private var balanceSnapshotAt: Long? = null
    private var balanceLoadError: String? = null

    // M-Pesa
    private lateinit var mpesaService: MpesaService
    private val mpesaTransactions = mutableListOf<MpesaTransaction>()
    private var mpesaListener: ListenerRegistration? = null
    private val mpesaExecutor = Executors.newSingleThreadExecutor()
    private var mpesaProcessing = false
    private var mpesaLoadError: String? = null

    // Colors
    private val C_PINE = 0xFF18B8AC.toInt()
    private val C_PINE_DARK = 0xFF183D82.toInt()
    private val C_INK = 0xFFF5F7FA.toInt()
    private val C_INK_SOFT = 0xFFC3CBD5.toInt()
    private val C_SURFACE = 0xFF24272D.toInt()
    private val C_BORDER = 0xFF373B43.toInt()
    private val C_MUTED = 0xFF9AA4B0.toInt()
    private val C_SOFT_BLUE = 0xFF20363B.toInt()
    private val C_ERROR_TEXT = 0xFFFF8585.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            startActivity(Intent(this, AuthActivity::class.java).apply {
                if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
                    putExtra(Intent.EXTRA_TEXT, intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.take(4_000))
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                }
            })
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
        chatStore = userStore.collection("chatMessages")
        balanceStore = userStore.collection("settings").document("currentBalance")
        mpesaService = MpesaService()
        chatComposer = findViewById(R.id.chatComposer)
        chatInput = findViewById(R.id.chatInput)
        chatSendButton = findViewById(R.id.chatSend)

        listOf(
            R.id.homeTab to "Home",
            R.id.transactionsTab to "Transactions",
            R.id.paymentsTab to "Payments",
            R.id.chatTab to "Chat",
            R.id.goalsTab to "Goals",
            R.id.profileTab to "Profile"
        ).forEach { (id, destination) ->
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
        balanceListener = balanceStore.addSnapshotListener { snapshot, error ->
            if (error != null) {
                balanceLoadError = "Could not load current balance: ${error.localizedMessage ?: "Check your connection."}"
            } else {
                balanceLoadError = null
                balanceSnapshotCents = snapshot?.getLong("balanceCents")
                balanceSnapshotAt = snapshot?.getLong("updatedAt")
            }
            if (page == "Home") show("Home")
        }
        goalListener = goalStore.addSnapshotListener { snapshot, error ->
            if (error != null) errorMessage = "Could not load goals: ${error.localizedMessage}"
            else if (snapshot != null) {
                goals.clear()
                goals.addAll(snapshot.documents.filterIsInstance<QueryDocumentSnapshot>())
            }
            if (page == "Goals") show(page)
        }
        loadChatHistory()
        loadMpesaHistory()
        show("Home")
        handleSharedMpesaMessage(intent)
    }

    override fun onDestroy() {
        transactionListener?.remove()
        goalListener?.remove()
        balanceListener?.remove()
        chatListener?.remove()
        mpesaListener?.remove()
        chatExecutor.shutdownNow()
        mpesaExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun loadChatHistory() {
        chatListener?.remove()
        chatHistoryLoaded = false
        chatLoadError = null
        updateChatControls()
        chatListener = chatStore.orderBy("createdAt", Query.Direction.ASCENDING).limitToLast(60)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    val permissionDenied = error is com.google.firebase.firestore.FirebaseFirestoreException &&
                        error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED
                    chatLoadError = if (permissionDenied) {
                        "Firestore rules are blocking chat history. Publish firestore.rules in Firebase Console, then tap here to retry."
                    } else {
                        "Could not load saved chat: ${error.localizedMessage}"
                    }
                    if (page == "Chat") {
                        renderChatMessages()
                        setChatStatus(chatLoadError.orEmpty())
                    }
                    updateChatControls()
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                chatDocuments.clear()
                chatDocuments.addAll(snapshot.documents.filterIsInstance<QueryDocumentSnapshot>())
                chatHistoryLoaded = true
                chatLoadError = null
                if (chatService == null) {
                    try {
                        chatService = FinancialChatService(this@HomeActivity).apply {
                            restore(chatDocuments.mapNotNull { record ->
                                val sender = record.getString("sender") ?: return@mapNotNull null
                                val text = record.getString("text") ?: return@mapNotNull null
                                PersistedChatTurn(sender, text)
                            })
                        }
                    } catch (setupError: Exception) {
                        chatSetupError = "Gemini setup failed: ${setupError.localizedMessage ?: "Check Firebase AI Logic setup."}"
                    }
                }
                if (page == "Chat") {
                    renderChatMessages()
                    setChatStatus(when {
                        chatSending -> "PesaPath is thinking…"
                        else -> chatSetupError.orEmpty()
                    })
                    updateChatControls()
                }
            }
    }

    private fun loadMpesaHistory() {
        mpesaListener?.remove()
        mpesaListener = mpesaService.streamTransactions().addSnapshotListener { snapshot, error ->
            if (error != null) {
                mpesaLoadError = "Could not load payment history: ${error.localizedMessage ?: "Check your connection."}"
                if (page == "Payments") show("Payments")
                return@addSnapshotListener
            }
            if (snapshot == null) return@addSnapshotListener
            mpesaLoadError = null
            mpesaTransactions.clear()
            mpesaTransactions.addAll(snapshot.documents.mapNotNull { doc ->
                val phone = doc.getString("phone") ?: return@mapNotNull null
                val amountCents = doc.getLong("amountCents") ?: return@mapNotNull null
                val status = doc.getString("status") ?: "pending"
                val checkoutRequestId = doc.getString("checkoutRequestId")
                val merchantRequestId = doc.getString("merchantRequestId")
                val resultCode = doc.get("resultCode")?.toString()
                val resultDesc = doc.getString("resultDesc")
                val createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                MpesaTransaction(doc.id, phone, amountCents, status, checkoutRequestId, merchantRequestId, resultCode, resultDesc, createdAt)
            })
            if (page == "Payments") show("Payments")
        }
    }

    private fun handleSharedMpesaMessage(sharedIntent: Intent?) {
        if (sharedIntent?.action != Intent.ACTION_SEND || sharedIntent.type != "text/plain") return
        val text = sharedIntent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val importedBalance = MpesaSmsParser.parseBalanceCents(text)
        if (importedBalance != null) {
            AlertDialog.Builder(this)
                .setTitle("Import M-Pesa balance?")
                .setMessage("PesaPath read ${money(importedBalance)} from the balance message you shared. Use this as your new starting balance?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Use balance") { _, _ -> saveBalanceSnapshot(importedBalance) }
                .show()
            return
        }
        val imported = MpesaSmsParser.parse(text)
        if (imported == null) {
            AlertDialog.Builder(this)
                .setTitle("Could not read this payment")
                .setMessage("Share a completed M-Pesa payment message. Incoming payments, reversals, and unsupported messages cannot be added as spending.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        transactionForm(importedMessage = imported)
    }

    private fun showMpesaImportHelp() {
        AlertDialog.Builder(this)
            .setTitle("Import from M-Pesa")
            .setMessage(
                "To import your balance, share a M-Pesa balance-inquiry message with PesaPath and confirm the amount. " +
                    "To add spending, share a completed payment message and review its details. PesaPath only receives the message you select; it does not read your inbox."
            )
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun darajaSourceId(transaction: MpesaTransaction): String {
        val source = transaction.id ?: transaction.checkoutRequestId
            ?: "${transaction.phone}:${transaction.createdAt}:${transaction.amountCents}"
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest("daraja:$source".toByteArray(Charsets.UTF_8))
        return "mpesa_" + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun isAlreadyImported(sourceId: String): Boolean =
        transactions.any { it.getString("sourceMpesaId") == sourceId }

    private fun currentBalanceCents(): Long? {
        val baseBalance = balanceSnapshotCents ?: return null
        val snapshotAt = balanceSnapshotAt ?: return baseBalance
        val spendingSinceSnapshot = transactions.sumOf { record ->
            val recordedAt = record.getLong("recordedAt") ?: return@sumOf 0L
            if (recordedAt > snapshotAt) record.getLong("amountCents") ?: 0L else 0L
        }
        return baseBalance - spendingSinceSnapshot
    }

    private fun showBalanceUpdateDialog() {
        val amountInput = EditText(this).apply {
            hint = "Amount in KES"
            inputType = 8194
            setText(balanceSnapshotCents?.let {
                BigDecimal.valueOf(it, 2).toPlainString()
            }.orEmpty())
            setSingleLine(true)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 8.dp, 24.dp, 0)
            addView(amountInput, LinearLayout.LayoutParams(-1, 56.dp))
        }
        AlertDialog.Builder(this)
            .setTitle("Update current balance")
            .setMessage("Enter the balance you have now. New spending added afterward will be deducted automatically.")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val cents = parseCents(amountInput.text.toString())
                        if (cents == null || cents < 0L) {
                            amountInput.error = "Enter a valid non-negative balance"
                            return@setOnClickListener
                        }
                        saveBalanceSnapshot(cents)
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }

    private fun saveBalanceSnapshot(balanceCents: Long) {
        val updatedAt = System.currentTimeMillis()
        balanceStore.set(mapOf("balanceCents" to balanceCents, "updatedAt" to updatedAt))
            .addOnSuccessListener {
                balanceSnapshotCents = balanceCents
                balanceSnapshotAt = updatedAt
                balanceLoadError = null
                if (page == "Home") show("Home")
            }
            .addOnFailureListener { error ->
                AlertDialog.Builder(this)
                    .setTitle("Could not update balance")
                    .setMessage(error.localizedMessage ?: "Check your connection and try again.")
                    .setPositiveButton("OK", null)
                    .show()
            }
    }

    private fun show(destination: String) {
        page = destination
        chatComposer.visibility = if (destination == "Chat") View.VISIBLE else View.GONE
        content.removeAllViews()
        heading.text = when (destination) {
            "Home" -> "Dashboard"
            "Transactions" -> "Activity"
            "Payments" -> "Payments"
            "Chat" -> "Chat"
            "Goals" -> "Savings Goals"
            else -> "Profile"
        }
        val tabs = listOf(
            Triple(R.id.homeTab, "Home", R.drawable.ic_home),
            Triple(R.id.transactionsTab, "Transactions", R.drawable.ic_activity),
            Triple(R.id.paymentsTab, "Payments", R.drawable.ic_payments),
            Triple(R.id.chatTab, "Chat", R.drawable.ic_chat),
            Triple(R.id.goalsTab, "Goals", R.drawable.ic_goals),
            Triple(R.id.profileTab, "Profile", R.drawable.ic_profile)
        )
        tabs.forEach { (id, tab, icon) ->
            val selected = destination == tab
            findViewById<Button>(id).apply {
                setTextColor(if (selected) C_PINE else C_INK_SOFT)
                val drawable = AppCompatResources.getDrawable(this@HomeActivity, icon)?.mutate()
                drawable?.let { DrawableCompat.setTint(it, if (selected) C_PINE else C_MUTED) }
                setCompoundDrawablesWithIntrinsicBounds(null, drawable, null, null)
                compoundDrawablePadding = 3.dp
                gravity = Gravity.CENTER
                setPadding(1.dp, 5.dp, 1.dp, 3.dp)
                background = roundedBackground(
                    if (selected) C_SOFT_BLUE else Color.TRANSPARENT,
                    14.dp
                )
            }
        }
        errorMessage?.let { text(it, 14f).setTextColor(C_ERROR_TEXT) }
        when (destination) {
            "Home" -> dashboard()
            "Transactions" -> transactionList()
            "Payments" -> payments()
            "Chat" -> assistant()
            "Goals" -> goalList()
            "Profile" -> profile()
        }
    }

    private fun dashboard() {
        val user = FirebaseAuth.getInstance().currentUser
        val name = user?.displayName?.takeIf { it.isNotBlank() } ?: user?.email?.substringBefore('@') ?: "there"
        val welcome = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val greeting = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addText(greeting, "Good day,", 13f, false, C_MUTED)
        addText(greeting, name, 22f, true, C_INK, top = 2.dp)
        welcome.addView(greeting, LinearLayout.LayoutParams(0, -2, 1f))
        val profileIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_profile)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            background = roundedBackground(C_PINE_DARK, 18.dp)
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
            isClickable = true
            isFocusable = true
            setOnClickListener { show("Profile") }
        }
        welcome.addView(profileIcon, LinearLayout.LayoutParams(48.dp, 48.dp))
        content.addView(welcome)
        val balanceCard = panel()
        addText(balanceCard, "CURRENT BALANCE", 12f, true, C_MUTED)
        val currentBalance = currentBalanceCents()
        addText(
            balanceCard,
            if (balanceLoadError != null) "Unavailable" else currentBalance?.let(::money) ?: "Set your balance",
            26f,
            true,
            C_INK,
            top = 6.dp
        )
        val balanceDetail = when {
            balanceLoadError != null -> balanceLoadError.orEmpty()
            balanceSnapshotCents == null -> "Add a starting balance or import a balance message"
            else -> "New spending is deducted from the balance you set"
        }
        addText(balanceCard, balanceDetail, 12f, false, C_MUTED, top = 4.dp)
        balanceCard.addView(styledButton("Update balance", false, ::showBalanceUpdateDialog),
            LinearLayout.LayoutParams(-1, 48.dp).apply { topMargin = 10.dp })
        balanceCard.addView(styledButton("Import M-Pesa balance", false, ::showMpesaImportHelp),
            LinearLayout.LayoutParams(-1, 48.dp).apply { topMargin = 8.dp })
        content.addView(balanceCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 16.dp })
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
        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 20.dp, 20.dp, 20.dp)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(C_PINE.toInt(), C_PINE_DARK.toInt())).apply { cornerRadius = 22.dp.toFloat() }
        }
        val summaryHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val walletIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_payments)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }
        summaryHeading.addView(walletIcon, LinearLayout.LayoutParams(22.dp, 22.dp).apply { marginEnd = 8.dp })
        addText(summaryHeading, "MONTHLY SPENDING", 12f, true, 0xFFE6F7FF.toInt())
        summary.addView(summaryHeading)
        addText(summary, SimpleDateFormat("MMMM yyyy", Locale.UK).format(Calendar.getInstance().time),
            14f, false, Color.WHITE)
        addText(summary, money(total), 30f, true, Color.WHITE)
        addText(summary, "${monthly.size} transactions this month", 13f, false, 0xFFB9E5FF.toInt())
        content.addView(summary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 18.dp })

        text("Quick actions", 18f, true)
        val firstActionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        firstActionRow.addView(actionTile(R.drawable.ic_add, "Add expense", "Record a purchase") {
            transactionForm(null)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = 7.dp })
        firstActionRow.addView(actionTile(R.drawable.ic_payments, "M-Pesa activity", "View payments") {
            show("Payments")
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 7.dp })
        content.addView(firstActionRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.dp })
        val secondActionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        secondActionRow.addView(actionTile(R.drawable.ic_sms, "Import M-Pesa", "Share a payment SMS") {
            showMpesaImportHelp()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = 7.dp })
        secondActionRow.addView(actionTile(R.drawable.ic_goals, "Savings goals", "Track your plans") {
            show("Goals")
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 7.dp })
        content.addView(secondActionRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.dp })

        val breakdownCard = panel()
        addText(breakdownCard, "Spending by category", 17f, true, C_INK)
        val breakdown = categories.map { category ->
            category to monthly.filter { it.getString("category") == category }
                .sumOf { it.getLong("amountCents") ?: 0L }
        }
        if (total == 0L) addText(breakdownCard, "No transactions this month yet.", 14f, false, C_MUTED)
        breakdown.filter { it.second > 0L }.forEach { (category, amount) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            addText(row, category, 14f, false, C_INK, 0, 0, 0, 0, 1f)
            addText(row, money(amount), 14f, true, C_INK, 0, 0, 0, 0)
            breakdownCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dp })
            breakdownCard.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = (amount.toDouble() * 100 / total).toInt().coerceIn(0, 100)
                progressTintList = android.content.res.ColorStateList.valueOf(C_PINE)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF3B424B.toInt())
            }, LinearLayout.LayoutParams(-1, 8.dp).apply { topMargin = 6.dp })
        }
        content.addView(breakdownCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 18.dp })

        val recentHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addText(recentHeading, "Recent activity", 18f, true, C_INK, 0, 0, 0, 0, 1f)
        val viewAll = TextView(this).apply {
            text = "View all"
            textSize = 13f
            setTextColor(C_PINE)
            setOnClickListener { show("Transactions") }
        }
        recentHeading.addView(viewAll)
        content.addView(recentHeading, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 22.dp })
        val recent = transactions.sortedByDescending { it.getLong("date") ?: 0L }.take(3)
        if (recent.isEmpty()) {
            val empty = panel()
            addText(empty, "No transactions yet. Add your first purchase to see it here.",
                14f, false, C_MUTED)
            content.addView(empty, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.dp })
        }
        recent.forEach { record ->
            val item = panel().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val category = record.getString("category") ?: "Other"
            val subcategory = record.getString("subcategory").orEmpty()
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            addText(details, listOf(category, subcategory).filter { it.isNotBlank() }.joinToString("  ·  "),
                15f, true, C_INK)
            addText(details, dateFormat.format(record.getLong("date") ?: 0L), 12f, false, C_MUTED)
            item.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            addText(item, "-${money(record.getLong("amountCents") ?: 0L)}", 14f, true, C_INK)
            content.addView(item, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 9.dp })
        }
    }

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16.dp, 15.dp, 16.dp, 15.dp)
        background = GradientDrawable().apply {
            setColor(C_SURFACE)
            cornerRadius = 18.dp.toFloat()
            setStroke(1.dp, C_BORDER)
        }
    }

    private fun roundedBackground(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp.toFloat()
        }

    private fun actionTile(icon: Int, title: String, subtitle: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 128.dp
            setPadding(16.dp, 16.dp, 14.dp, 16.dp)
            background = roundedBackground(C_SURFACE, 18.dp).apply { setStroke(1.dp, C_BORDER) }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            val iconHolder = LinearLayout(this@HomeActivity).apply {
                gravity = Gravity.CENTER
                background = roundedBackground(C_SOFT_BLUE, 14.dp)
            }
            iconHolder.addView(ImageView(this@HomeActivity).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(C_PINE)
            }, LinearLayout.LayoutParams(24.dp, 24.dp))
            addView(iconHolder, LinearLayout.LayoutParams(46.dp, 46.dp).apply { bottomMargin = 10.dp })
            addText(this, title, 15f, true, C_INK)
            addText(this, subtitle, 12f, false, C_MUTED, top = 4.dp)
        }

    private fun addText(
        parent: LinearLayout,
        value: String,
        size: Float,
        bold: Boolean,
        color: Int,
        left: Int = 0,
        top: Int = 0,
        right: Int = 0,
        bottom: Int = 0,
        weight: Float = 0f
    ): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
        setPadding(left, top, right, bottom)
        parent.addView(this, LinearLayout.LayoutParams(0.takeIf { weight > 0f } ?: -2, -2, weight)
            .apply { if (weight == 0f) width = -1 })
    }

    private fun pageBanner(eyebrow: String, title: String, detail: String) {
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 18.dp, 20.dp, 18.dp)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(C_PINE.toInt(), C_PINE_DARK.toInt())).apply { cornerRadius = 20.dp.toFloat() }
        }
        addText(banner, eyebrow, 11f, true, 0xFFB9E5FF.toInt())
        addText(banner, title, 21f, true, Color.WHITE, top = 6.dp)
        addText(banner, detail, 13f, false, 0xFFDCEBFF.toInt(), top = 4.dp)
        content.addView(banner, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4.dp })
    }

    private fun emptyPanel(title: String, detail: String) {
        val empty = panel()
        addText(empty, title, 16f, true, C_INK)
        addText(empty, detail, 13f, false, C_MUTED, top = 4.dp)
        content.addView(empty, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dp })
    }

    private fun transactionList() {
        pageBanner("SPENDING ACTIVITY", "Every move, in one place", "${transactions.size} transactions recorded")
        button("+ Add Transaction") { transactionForm(null) }
        val importActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        importActions.addView(
            styledButton("Import M-Pesa SMS", false, { showMpesaImportHelp() }),
            LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginEnd = 5.dp }
        )
        importActions.addView(
            styledButton("M-Pesa activity", false, { show("Payments") }),
            LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginStart = 5.dp }
        )
        content.addView(importActions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.dp })
        val sorted = transactions.sortedByDescending { it.getLong("date") ?: 0L }
        if (sorted.isEmpty()) emptyPanel("No activity yet", "Add your first transaction to start tracking your spending.")
        sorted.forEach { record ->
            val item = panel()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val marker = TextView(this).apply {
                text = (record.getString("category") ?: "O").take(1).uppercase(Locale.UK)
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(C_PINE)
                    shape = GradientDrawable.OVAL
                }
            }
            row.addView(marker, LinearLayout.LayoutParams(42.dp, 42.dp).apply { marginEnd = 12.dp })
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val category = record.getString("category") ?: "Other"
            val subcategory = record.getString("subcategory").orEmpty()
            addText(details, listOf(category, subcategory).filter { it.isNotBlank() }.joinToString("  ·  "),
                15f, true, C_INK)
            addText(details, record.getString("note").orEmpty().ifBlank {
                dateFormat.format(record.getLong("date") ?: 0L)
            }, 12f, false, C_MUTED)
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            val amount = TextView(this).apply {
                text = "-${money(record.getLong("amountCents") ?: 0L)}"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(C_INK)
            }
            row.addView(amount)
            item.addView(row)
            addText(item, dateFormat.format(record.getLong("date") ?: 0L), 11f, false,
                0xFF71809B.toInt(), left = 54.dp, top = 5.dp)
            content.addView(item, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.dp })
            actions({ transactionForm(record) }, { delete(transactionStore, record.id, "transaction") })
        }
    }

    private fun goalList() {
        pageBanner("SAVINGS PLAN", "Small steps add up", "${goals.size} goals in your plan")
        button("+ New Goal") { goalForm(null) }
        if (goals.isEmpty()) emptyPanel("Start with a goal", "Give your next milestone a name, target, and date.")
        goals.sortedBy { it.getLong("dueDate") ?: Long.MAX_VALUE }.forEach { record ->
            val target = record.getLong("targetCents") ?: 0L
            val saved = record.getLong("savedCents") ?: 0L
            val percent = if (target > 0) (saved.toDouble() * 100 / target).toInt().coerceAtLeast(0) else 0
            val goal = panel()
            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            addText(titleRow, record.getString("title") ?: "Savings goal", 17f, true,
                C_INK, weight = 1f)
            val percentLabel = TextView(this).apply {
                text = "$percent%"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(C_PINE)
            }
            titleRow.addView(percentLabel)
            goal.addView(titleRow)
            addText(goal, "${money(saved)} saved of ${money(target)}", 14f, false,
                C_INK_SOFT, top = 8.dp)
            goal.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = percent.coerceIn(0, 100)
                progressTintList = android.content.res.ColorStateList.valueOf(C_PINE)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE6EDF7.toInt())
            }, LinearLayout.LayoutParams(-1, 8.dp).apply { topMargin = 12.dp })
            addText(goal, "Due ${dateFormat.format(record.getLong("dueDate") ?: 0L)}", 12f, false,
                C_MUTED, top = 8.dp)
            content.addView(goal, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dp })
            actions({ goalForm(record) }, { delete(goalStore, record.id, "goal") })
        }
    }

    private fun payments() {
        pageBanner("M-PESA ACTIVITY", "Payments and imports", "Track PesaPath payments or import a shared payment message")
        mpesaLoadError?.let { text(it, 13f).setTextColor(C_ERROR_TEXT) }
        styledButton("Import a M-Pesa SMS", false, { showMpesaImportHelp() }).also {
            content.addView(it, LinearLayout.LayoutParams(-1, 48.dp).apply { topMargin = 10.dp })
        }
        text("Start a payment", 17f, true)
        val phone = input("Phone number", "07XXXXXXXX", 1)
        val amount = input("Amount (KES)", "", 8194)
        val statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(C_MUTED)
            setPadding(0, 0, 0, 8.dp)
            visibility = View.GONE
        }
        content.addView(statusView)
        lateinit var pay: Button
        pay = button("Pay with M-Pesa") {
            val p = phone.text.toString().trim()
            val cents = parseCents(amount.text.toString())
            if (!p.matches(Regex("^(07|01)[0-9]{8}$|^254(7|1)[0-9]{8}$|^\\+254(7|1)[0-9]{8}$"))) {
                phone.error = "Enter a valid Kenyan mobile number"
                return@button
            }
            if (cents == null || cents < 100L || cents % 100L != 0L) {
                amount.error = "Enter a whole amount of at least KES 1"
                return@button
            }
            if (cents > 25_000_000L) {
                amount.error = "The maximum single M-Pesa payment is KES 250,000"
                return@button
            }
            pay.isEnabled = false
            mpesaProcessing = true
            statusView.visibility = View.VISIBLE
            statusView.text = "Sending a secure payment request…"
            statusView.setTextColor(C_MUTED)
            mpesaExecutor.execute {
                try {
                    val result = runBlocking { mpesaService.stkPush(p, cents) }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        mpesaProcessing = false
                        if (page != "Payments") return@runOnUiThread
                        pay.isEnabled = true
                        statusView.text = "Request sent. Enter your M-Pesa PIN on your phone. Reference ${result.transactionId}. The status will update here automatically."
                        statusView.setTextColor(C_PINE)
                        amount.text.clear()
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        mpesaProcessing = false
                        if (page != "Payments") return@runOnUiThread
                        pay.isEnabled = true
                        statusView.text = "Could not start the payment: ${error.localizedMessage ?: "Check your connection and try again."}"
                        statusView.setTextColor(C_ERROR_TEXT)
                    }
                }
            }
        }
        pay.isEnabled = !mpesaProcessing
        val recent = mpesaTransactions.sortedByDescending { it.createdAt }.take(6)
        if (recent.isEmpty()) {
            emptyPanel("No payments yet", "Your M-Pesa payments will appear here once you send money.")
        } else {
            text("PesaPath Daraja payments", 17f, true)
            recent.forEach { tx ->
                val item = panel()
                val transactionRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                addText(details, "M-Pesa payment", 14f, true, C_INK)
                addText(details, "${money(tx.amountCents)}  ·  ${statusLabel(tx.status)}", 12f, false,
                    if (tx.status == MpesaService.STATUS_COMPLETED) C_PINE else C_MUTED)
                transactionRow.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
                addText(transactionRow, dateFormat.format(tx.createdAt), 11f, false, C_MUTED)
                item.addView(transactionRow)
                if (tx.status == MpesaService.STATUS_COMPLETED) {
                    val sourceId = darajaSourceId(tx)
                    val imported = isAlreadyImported(sourceId)
                    val importButton = styledButton(
                        if (imported) "Added to activity" else "Add to spending",
                        primary = !imported,
                        onClick = {
                            if (!imported) {
                                transactionForm(
                                    importedMessage = ImportedMpesaMessage(
                                        sourceId = sourceId,
                                        amountCents = tx.amountCents,
                                        date = tx.createdAt,
                                        note = "M-Pesa payment"
                                    )
                                )
                            }
                        }
                    ).apply {
                        isEnabled = !imported
                    }
                    item.addView(importButton, LinearLayout.LayoutParams(-1, 42.dp).apply { topMargin = 12.dp })
                }
                content.addView(item, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.dp })
            }
        }
    }

    private fun maskPhone(phone: String): String = if (phone.length >= 4) phone.substring(0, 4) + "****" + phone.substring(phone.length - 4) else phone

    private fun statusLabel(status: String): String = when (status) {
        MpesaService.STATUS_COMPLETED -> "Completed"
        MpesaService.STATUS_FAILED -> "Failed"
        MpesaService.STATUS_CANCELLED -> "Cancelled"
        else -> "Pending"
    }

    private fun profile() {
        val email = FirebaseAuth.getInstance().currentUser?.email.orEmpty()
        pageBanner("YOUR PESAPATH", "Account details", "Manage your signed-in session")
        val account = panel().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val avatar = TextView(this).apply {
            text = email.firstOrNull()?.uppercaseChar()?.toString() ?: "P"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(C_PINE)
                shape = GradientDrawable.OVAL
            }
        }
        account.addView(avatar, LinearLayout.LayoutParams(52.dp, 52.dp).apply { marginEnd = 14.dp })
        val identity = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addText(identity, "Signed in", 12f, false, C_MUTED)
        addText(identity, email, 15f, true, C_INK, top = 3.dp)
        account.addView(identity, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(account, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14.dp })
        button("Sign out") {
            FirebaseAuth.getInstance().signOut()
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
        }
    }

    private fun assistant() {
        pageBanner("PESAPATH CHAT", "A clearer money conversation", "Your financial assistant")
        val privacyNote = panel()
        addText(privacyNote, "Your AI assistant", 15f, true, C_INK)
        addText(privacyNote,
            "Monthly spending totals and savings goals are shared with Gemini to answer your questions. Don't enter passwords, PINs, or account numbers.",
            12f, false, C_MUTED, top = 5.dp)
        content.addView(privacyNote, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dp })

        chatMessagesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(chatMessagesContainer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dp })
        chatStatusView = TextView(this).apply {
            textSize = 12f
            setTextColor(C_MUTED)
            visibility = View.GONE
        }
        content.addView(chatStatusView)
        chatSendButton.setOnClickListener { sendChatMessage() }
        chatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendChatMessage()
                true
            } else false
        }
        renderChatMessages()
        chatLoadError?.let { setChatStatus(it) }
        chatSetupError?.let { setChatStatus(it) }
        if (chatSending) setChatStatus("PesaPath is thinking…")
        updateChatControls()
    }

    private fun renderChatMessages() {
        val container = chatMessagesContainer ?: return
        container.removeAllViews()
        if (chatDocuments.isEmpty()) {
            if (!chatHistoryLoaded) {
                addText(container, chatLoadError ?: "Loading your conversation…", 14f, false, C_MUTED)
            } else {
                val welcome = panel()
                addText(welcome, "Hi, I'm your PesaPath assistant.", 16f, true, C_INK)
                addText(welcome, "Ask about your spending or savings goals.", 13f, false, C_MUTED, top = 4.dp)
                container.addView(welcome, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10.dp })
                listOf("How much have I spent this month?", "Where is most of my spending going?", "Help me plan a savings goal")
                    .forEach { suggestion ->
                        val prompt = TextView(this).apply {
                            text = suggestion
                            textSize = 13f
                            setTextColor(C_PINE)
                            setPadding(14.dp, 11.dp, 14.dp, 11.dp)
                            background = GradientDrawable().apply {
                                setColor(C_SOFT_BLUE)
                                cornerRadius = 14.dp.toFloat()
                            }
                            isClickable = true
                            isFocusable = true
                            setOnClickListener {
                                chatInput.setText(suggestion)
                                sendChatMessage()
                            }
                        }
                        container.addView(prompt, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8.dp })
                    }
            }
        }
        chatDocuments.forEach { record ->
            val sender = record.getString("sender") ?: "assistant"
            val message = plain(record.getString("text").orEmpty())
            if (message.isNotBlank()) addChatBubble(container, sender, message)
        }
        if (chatSending) setChatStatus("PesaPath is thinking…")
        findViewById<ScrollView>(R.id.homeScroll).post {
            findViewById<ScrollView>(R.id.homeScroll).fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun addChatBubble(container: LinearLayout, sender: String, message: String) {
        val isUser = sender == "user"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isUser) Gravity.END else Gravity.START
            setPadding(0, 0, 0, 9.dp)
        }
        val bubble = TextView(this).apply {
            if (!isUser) autoLinkMask = android.text.util.Linkify.WEB_URLS
            text = message
            textSize = 14f
            setTextColor(C_INK)
            setPadding(14.dp, 11.dp, 14.dp, 11.dp)
            maxWidth = 280.dp
            background = GradientDrawable().apply {
                setColor(if (isUser) C_SOFT_BLUE else C_SURFACE)
                cornerRadius = 16.dp.toFloat()
                if (!isUser) setStroke(1.dp, C_BORDER)
            }
        }
        row.addView(bubble, LinearLayout.LayoutParams(-2, -2))
        container.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun sendChatMessage() {
        val question = chatInput.text.toString().trim()
        if (question.isBlank() || chatSending) return
        val service = chatService
        if (!chatHistoryLoaded || service == null) {
            setChatStatus(chatSetupError ?: chatLoadError ?: "Chat is still connecting. Try again shortly.")
            return
        }

        chatSending = true
        chatInput.text.clear()
        setChatStatus("PesaPath is thinking…")
        updateChatControls()
        val summary = financialSummary()
        val userMessage = mapOf(
            "sender" to "user",
            "text" to question.take(1000),
            "createdAt" to System.currentTimeMillis()
        )
        chatStore.add(userMessage)
            .addOnSuccessListener {
                chatExecutor.execute {
                    try {
                        val response = runBlocking { service.reply(question, summary) }
                            .take(4000)
                            .ifBlank { "I couldn't form a response just now. Please try again." }
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            chatStore.add(mapOf(
                                "sender" to "assistant",
                                "text" to plain(response),
                                "createdAt" to System.currentTimeMillis()
                            )).addOnCompleteListener { saveTask ->
                                chatSending = false
                                if (saveTask.isSuccessful) setChatStatus("")
                                else setChatStatus("The reply arrived but couldn't be saved. Check your connection and chat rules.")
                                updateChatControls()
                            }
                        }
                    } catch (error: Exception) {
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            chatSending = false
                            setChatStatus("Couldn't reply: ${error.localizedMessage ?: "Check your connection."}")
                            updateChatControls()
                        }
                    }
                }
            }
            .addOnFailureListener { error ->
                chatSending = false
                setChatStatus("Couldn't save your message: ${error.localizedMessage ?: "Check your connection."}")
                updateChatControls()
            }
    }

    private fun plain(text: String): String = text
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        .replace(Regex("(?m)^\\s*\\d+[.)]\\s+"), "")
        .replace(Regex("(?m)^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$"), "")
        .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
        .replace(Regex("\\*\\*(.+?)\\*\\*|__(.+?)__"), "$1$2")
        .replace(Regex("(?<!\\w)\\*(\\S(?:.*?\\S)?)\\*(?!\\w)|(?<!\\w)_(\\S(?:.*?\\S)?)_(?!\\w)"), "$1$2")
        .replace("```", "")
        .replace("`", "")
        .replace("#", "")
        .replace("*", "")
        .replace(">", "")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private fun financialSummary(): String {
        val now = Calendar.getInstance()
        val monthStart = (now.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val nextMonth = (now.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            add(Calendar.MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val monthly = transactions.filter { (it.getLong("date") ?: 0L) in monthStart until nextMonth }
        val total = monthly.sumOf { it.getLong("amountCents") ?: 0L }
        val categoriesSummary = categories.mapNotNull { category ->
            val amount = monthly.filter { it.getString("category") == category }
                .sumOf { it.getLong("amountCents") ?: 0L }
            if (amount > 0L) "$category: ${money(amount)}" else null
        }.joinToString()
        val goalsSummary = goals.take(10).joinToString("; ") { goal ->
            val title = goal.getString("title") ?: "Savings goal"
            "$title: ${money(goal.getLong("savedCents") ?: 0L)} saved of ${money(goal.getLong("targetCents") ?: 0L)}"
        }.ifBlank { "No savings goals recorded." }
        return "Month: ${SimpleDateFormat("MMMM yyyy", Locale.UK).format(now.time)}. " +
            "Spending: ${money(total)} across ${monthly.size} transactions. " +
            "By category: ${categoriesSummary.ifBlank { "no category spending recorded" }}. " +
            "Savings goals: $goalsSummary"
    }

    private fun setChatStatus(message: String) {
        chatStatusView?.apply {
            text = message
            visibility = if (message.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun updateChatControls() {
        if (!::chatSendButton.isInitialized) return
        chatSendButton.isEnabled = chatHistoryLoaded && chatService != null && !chatSending
    }

    private fun transactionForm(
        record: QueryDocumentSnapshot? = null,
        importedMessage: ImportedMpesaMessage? = null
    ) {
        page = "Transaction form"
        heading.text = when {
            record != null -> "Edit Transaction"
            importedMessage != null -> "Import Payment"
            else -> "Add Transaction"
        }
        content.removeAllViews()
        pageBanner(
            if (importedMessage != null) "M-PESA IMPORT" else "SPENDING ACTIVITY",
            if (importedMessage != null) "Review this payment" else "Record a transaction",
            if (importedMessage != null) "Check the details and choose a category before saving" else "Add the amount and details below"
        )
        val initialAmount = record?.getLong("amountCents") ?: importedMessage?.amountCents
        val amount = input("Amount (KES)", initialAmount?.let {
            BigDecimal.valueOf(it, 2).toPlainString()
        }.orEmpty(), 8194)
        text("Category", 14f)
        val initialCategory = record?.getString("category") ?: "Other"
        val category = Spinner(this).apply {
            adapter = ArrayAdapter(this@HomeActivity, android.R.layout.simple_spinner_dropdown_item, categories)
            setSelection(categories.indexOf(initialCategory).coerceAtLeast(0))
        }
        content.addView(category)
        text("Subcategory", 14f)
        val subcategory = Spinner(this)
        fun updateSubcategories(selected: String?) {
            val options = subcategories[category.selectedItem?.toString()] ?: listOf("Other")
            subcategory.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                options
            )
            subcategory.setSelection(options.indexOf(selected).coerceAtLeast(0))
        }
        category.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = if (categories[position] == initialCategory) {
                    record?.getString("subcategory") ?: "Other"
                } else {
                    "Other"
                }
                updateSubcategories(selected)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        content.addView(subcategory)
        updateSubcategories(record?.getString("subcategory") ?: "Other")
        var date = record?.getLong("date") ?: importedMessage?.date ?: System.currentTimeMillis()
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
        val note = input("Note (optional)", record?.getString("note") ?: importedMessage?.note.orEmpty(), 1)
        lateinit var save: Button
        save = button(if (importedMessage != null) "ADD TO ACTIVITY" else "SAVE TRANSACTION") {
            val cents = parseCents(amount.text.toString())
            if (cents == null || cents <= 0L) {
                amount.error = "Enter a positive amount with at most 2 decimal places"
                return@button
            }
            val data = mutableMapOf<String, Any>(
                "amountCents" to cents,
                "category" to category.selectedItem.toString(),
                "subcategory" to subcategory.selectedItem.toString(),
                "date" to date,
                "note" to note.text.toString().trim()
            )
            val recordedAt = record?.getLong("recordedAt")
                ?: if (record == null) {
                    importedMessage?.date ?: maxOf(System.currentTimeMillis(), (balanceSnapshotAt ?: 0L) + 1L)
                } else {
                    null
                }
            if (recordedAt != null) data["recordedAt"] = recordedAt
            val sourceId = importedMessage?.sourceId ?: record?.getString("sourceMpesaId")
            if (sourceId != null) data["sourceMpesaId"] = sourceId
            if (importedMessage != null) {
                saveImportedTransaction(sourceId = importedMessage.sourceId, data = data, save = save)
            } else {
                saveRecord(transactionStore, record?.id, data, save, "Transactions")
            }
        }
        button("Cancel") { show("Transactions") }
    }

    private fun saveImportedTransaction(sourceId: String, data: Map<String, Any>, save: Button) {
        save.isEnabled = false
        val reference = transactionStore.document(sourceId)
        FirebaseFirestore.getInstance().runTransaction { transaction ->
            if (transaction.get(reference).exists()) {
                throw IllegalStateException("This M-Pesa payment has already been added to activity.")
            }
            transaction.set(reference, data)
        }.addOnCompleteListener(this) { result ->
            save.isEnabled = true
            if (result.isSuccessful) {
                show("Transactions")
            } else {
                AlertDialog.Builder(this)
                    .setMessage(result.exception?.localizedMessage ?: "Could not import this payment.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun goalForm(record: QueryDocumentSnapshot?) {
        page = "Goal form"
        heading.text = if (record == null) "New Savings Goal" else "Edit Savings Goal"
        content.removeAllViews()
        pageBanner("SAVINGS PLAN", "Set your next milestone", "Choose a target and a date")
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
        row.addView(styledButton("Edit", false, onEdit), LinearLayout.LayoutParams(0, 44.dp, 1f).apply { marginEnd = 6.dp })
        row.addView(styledButton("Delete", false, onDelete, destructive = true), LinearLayout.LayoutParams(0, 44.dp, 1f).apply { marginStart = 6.dp })
        content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4.dp })
    }

    private fun text(value: String, size: Float, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(C_INK)
        if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
        includeFontPadding = false
        setPadding(0, 12.dp, 0, 8.dp)
        content.addView(this)
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        val secondary = label == "Cancel" || label.startsWith("Date:") || label.startsWith("Due:")
        return styledButton(label, !secondary, onClick).also {
            content.addView(it, LinearLayout.LayoutParams(-1, 52.dp).apply { topMargin = 12.dp })
        }
    }

    private fun styledButton(
        label: String,
        primary: Boolean,
        onClick: () -> Unit,
        destructive: Boolean = false
    ): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        minWidth = 0
        setTypeface(null, Typeface.BOLD)
        setTextColor(when {
            primary -> Color.WHITE
            destructive -> C_ERROR_TEXT
            else -> C_PINE
        })
        background = GradientDrawable().apply {
            setColor(when {
                primary -> C_PINE
                destructive -> 0xFFFBEDEE.toInt()
                else -> C_SURFACE
            }).apply {
                cornerRadius = 14.dp.toFloat()
                if (!primary && !destructive) setStroke(1.dp, C_BORDER)
            }
        }
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun input(hintText: String, value: String, type: Int): EditText = EditText(this).apply {
        hint = hintText
        setText(value)
        inputType = type
        textSize = 15f
        setTextColor(C_INK)
        setHintTextColor(C_MUTED)
        setPadding(16.dp, 0, 16.dp, 0)
        background = GradientDrawable().apply {
            setColor(C_SURFACE)
            cornerRadius = 14.dp.toFloat()
            setStroke(1.dp, C_BORDER)
        }
        content.addView(this, LinearLayout.LayoutParams(-1, 56.dp).apply { topMargin = 12.dp })
    }

    private fun parseCents(raw: String): Long? = try {
        BigDecimal(raw.trim()).movePointRight(2).longValueExact()
    } catch (_: Exception) { null }

    private fun money(cents: Long): String = "KES ${currency.format(BigDecimal.valueOf(cents, 2))}"
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
