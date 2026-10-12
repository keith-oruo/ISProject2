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
import android.widget.Button
import android.widget.EditText
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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
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
    private lateinit var chatStore: CollectionReference
    private lateinit var content: LinearLayout
    private lateinit var heading: TextView
    private lateinit var chatComposer: LinearLayout
    private lateinit var chatInput: EditText
    private lateinit var chatSendButton: Button
    private val chatDocuments = mutableListOf<QueryDocumentSnapshot>()
    private var transactionListener: ListenerRegistration? = null
    private var goalListener: ListenerRegistration? = null
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
        chatStore = userStore.collection("chatMessages")
        chatComposer = findViewById(R.id.chatComposer)
        chatInput = findViewById(R.id.chatInput)
        chatSendButton = findViewById(R.id.chatSend)

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
        loadChatHistory()
        show("Home")
    }

    override fun onDestroy() {
        transactionListener?.remove()
        goalListener?.remove()
        chatListener?.remove()
        chatExecutor.shutdownNow()
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
                        chatService = FinancialChatService().apply {
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

    private fun show(destination: String) {
        page = destination
        chatComposer.visibility = if (destination == "Chat") View.VISIBLE else View.GONE
        content.removeAllViews()
        heading.text = when (destination) {
            "Home" -> "Dashboard"
            "Transactions" -> "Transactions"
            "Goals" -> "Savings Goals"
            "Chat" -> "Chat"
            else -> "Profile"
        }
        listOf(R.id.homeTab to "Home", R.id.transactionsTab to "Transactions", R.id.chatTab to "Chat",
            R.id.goalsTab to "Goals", R.id.profileTab to "Profile").forEach { (id, tab) ->
            findViewById<Button>(id).setTextColor(if (destination == tab) 0xFF18B7B0.toInt() else Color.WHITE)
        }
        errorMessage?.let { text(it, 14f).setTextColor(0xFFAA3333.toInt()) }
        when (destination) {
            "Home" -> dashboard()
            "Transactions" -> transactionList()
            "Goals" -> goalList()
            "Chat" -> assistant()
            "Profile" -> profile()
        }
    }

    private fun dashboard() {
        val user = FirebaseAuth.getInstance().currentUser
        val name = user?.displayName?.substringBefore(' ') ?: user?.email?.substringBefore('@') ?: "there"
        text("Hi, $name", 24f, true)
        text("Your finances, at a glance", 14f)
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
                intArrayOf(0xFF174A9A.toInt(), 0xFF087FA3.toInt())).apply { cornerRadius = 22.dp.toFloat() }
        }
        addText(summary, "MONTHLY SPENDING", 12f, true, 0xFFD5E9FF.toInt())
        addText(summary, SimpleDateFormat("MMMM yyyy", Locale.UK).format(Calendar.getInstance().time),
            14f, false, Color.WHITE)
        addText(summary, money(total), 30f, true, Color.WHITE)
        addText(summary, "${monthly.size} transactions this month", 13f, false, 0xFFD5E9FF.toInt())
        content.addView(summary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 18.dp })

        text("Your shortcuts", 18f, true)
        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actionRow.addView(actionTile("+", "Add transaction", "Record spending") { transactionForm(null) },
            LinearLayout.LayoutParams(0, 104.dp, 1f).apply { marginEnd = 7.dp })
        actionRow.addView(actionTile("◎", "Savings goals", "Build your plan") { show("Goals") },
            LinearLayout.LayoutParams(0, 104.dp, 1f).apply { marginStart = 7.dp })
        content.addView(actionRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.dp })

        val breakdownCard = panel()
        addText(breakdownCard, "Spending by category", 17f, true, 0xFF142D66.toInt())
        val breakdown = categories.map { category ->
            category to monthly.filter { it.getString("category") == category }
                .sumOf { it.getLong("amountCents") ?: 0L }
        }
        if (total == 0L) addText(breakdownCard, "No transactions this month yet.", 14f, false, 0xFF60708D.toInt())
        breakdown.filter { it.second > 0L }.forEach { (category, amount) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            addText(row, category, 14f, false, 0xFF263C65.toInt(), 0, 0, 0, 0, 1f)
            addText(row, money(amount), 14f, true, 0xFF142D66.toInt(), 0, 0, 0, 0)
            breakdownCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dp })
            breakdownCard.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = (amount.toDouble() * 100 / total).toInt().coerceIn(0, 100)
                progressTintList = android.content.res.ColorStateList.valueOf(0xFF18B7B0.toInt())
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE6EDF7.toInt())
            }, LinearLayout.LayoutParams(-1, 8.dp).apply { topMargin = 6.dp })
        }
        content.addView(breakdownCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 18.dp })

        val recentHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addText(recentHeading, "Recent activity", 18f, true, 0xFF142D66.toInt(), 0, 0, 0, 0, 1f)
        val viewAll = TextView(this).apply {
            text = "View all"
            textSize = 13f
            setTextColor(0xFF087F91.toInt())
            setOnClickListener { show("Transactions") }
        }
        recentHeading.addView(viewAll)
        content.addView(recentHeading, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 22.dp })
        val recent = transactions.sortedByDescending { it.getLong("date") ?: 0L }.take(3)
        if (recent.isEmpty()) {
            val empty = panel()
            addText(empty, "No transactions yet. Add your first purchase to see it here.",
                14f, false, 0xFF60708D.toInt())
            content.addView(empty, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.dp })
        }
        recent.forEach { record ->
            val item = panel().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val category = record.getString("category") ?: "Other"
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            addText(details, category, 15f, true, 0xFF142D66.toInt())
            addText(details, dateFormat.format(record.getLong("date") ?: 0L), 12f, false, 0xFF60708D.toInt())
            item.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            addText(item, "-${money(record.getLong("amountCents") ?: 0L)}", 14f, true, 0xFF142D66.toInt())
            content.addView(item, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 9.dp })
        }
    }

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16.dp, 15.dp, 16.dp, 15.dp)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = 18.dp.toFloat()
            setStroke(1.dp, 0xFFE3EAF5.toInt())
        }
    }

    private fun actionTile(symbol: String, title: String, subtitle: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
            background = GradientDrawable().apply {
                setColor(0xFFE5EEFC.toInt())
                cornerRadius = 16.dp.toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addText(this, symbol, 21f, true, 0xFF087F91.toInt())
            addText(this, title, 14f, true, 0xFF142D66.toInt())
            addText(this, subtitle, 11f, false, 0xFF60708D.toInt())
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
                intArrayOf(0xFF174A9A.toInt(), 0xFF087FA3.toInt())).apply { cornerRadius = 20.dp.toFloat() }
        }
        addText(banner, eyebrow, 11f, true, 0xFFB9E5FF.toInt())
        addText(banner, title, 21f, true, Color.WHITE, top = 6.dp)
        addText(banner, detail, 13f, false, 0xFFDCEBFF.toInt(), top = 4.dp)
        content.addView(banner, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4.dp })
    }

    private fun emptyPanel(title: String, detail: String) {
        val empty = panel()
        addText(empty, title, 16f, true, 0xFF142D66.toInt())
        addText(empty, detail, 13f, false, 0xFF60708D.toInt(), top = 4.dp)
        content.addView(empty, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dp })
    }

    private fun transactionList() {
        pageBanner("SPENDING ACTIVITY", "Every move, in one place", "${transactions.size} transactions recorded")
        button("+ Add Transaction") { transactionForm(null) }
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
                    setColor(0xFF174A9A.toInt())
                    shape = GradientDrawable.OVAL
                }
            }
            row.addView(marker, LinearLayout.LayoutParams(42.dp, 42.dp).apply { marginEnd = 12.dp })
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            addText(details, record.getString("category") ?: "Other", 15f, true, 0xFF142D66.toInt())
            addText(details, record.getString("note").orEmpty().ifBlank {
                dateFormat.format(record.getLong("date") ?: 0L)
            }, 12f, false, 0xFF60708D.toInt())
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            val amount = TextView(this).apply {
                text = "-${money(record.getLong("amountCents") ?: 0L)}"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(0xFF142D66.toInt())
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
                0xFF142D66.toInt(), weight = 1f)
            val percentLabel = TextView(this).apply {
                text = "$percent%"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(0xFF087F91.toInt())
            }
            titleRow.addView(percentLabel)
            goal.addView(titleRow)
            addText(goal, "${money(saved)} saved of ${money(target)}", 14f, false,
                0xFF344867.toInt(), top = 8.dp)
            goal.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = percent.coerceIn(0, 100)
                progressTintList = android.content.res.ColorStateList.valueOf(0xFF18B7B0.toInt())
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE6EDF7.toInt())
            }, LinearLayout.LayoutParams(-1, 8.dp).apply { topMargin = 12.dp })
            addText(goal, "Due ${dateFormat.format(record.getLong("dueDate") ?: 0L)}", 12f, false,
                0xFF60708D.toInt(), top = 8.dp)
            content.addView(goal, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dp })
            actions({ goalForm(record) }, { delete(goalStore, record.id, "goal") })
        }
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
                setColor(0xFF174A9A.toInt())
                shape = GradientDrawable.OVAL
            }
        }
        account.addView(avatar, LinearLayout.LayoutParams(52.dp, 52.dp).apply { marginEnd = 14.dp })
        val identity = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addText(identity, "Signed in", 12f, false, 0xFF60708D.toInt())
        addText(identity, email, 15f, true, 0xFF142D66.toInt(), top = 3.dp)
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
        addText(privacyNote, "Your AI assistant", 15f, true, 0xFF142D66.toInt())
        addText(privacyNote,
            "Monthly spending totals and savings goals are shared with Gemini to answer your questions. Don't enter passwords, PINs, or account numbers.",
            12f, false, 0xFF60708D.toInt(), top = 5.dp)
        content.addView(privacyNote, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dp })

        chatMessagesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(chatMessagesContainer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dp })
        chatStatusView = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF60708D.toInt())
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
                addText(container, chatLoadError ?: "Loading your conversation…", 14f, false, 0xFF60708D.toInt())
            } else {
                val welcome = panel()
                addText(welcome, "Hi, I'm your PesaPath AI assistant.", 16f, true, 0xFF142D66.toInt())
                addText(welcome, "Ask about your spending or savings goals.", 13f, false, 0xFF60708D.toInt(), top = 4.dp)
                container.addView(welcome, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10.dp })
                listOf("How much have I spent this month?", "Where is most of my spending going?", "Help me plan a savings goal")
                    .forEach { suggestion ->
                        val prompt = TextView(this).apply {
                            text = suggestion
                            textSize = 13f
                            setTextColor(0xFF174A9A.toInt())
                            setPadding(14.dp, 11.dp, 14.dp, 11.dp)
                            background = GradientDrawable().apply {
                                setColor(0xFFE5EEFC.toInt())
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
            val message = record.getString("text").orEmpty()
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
            text = message
            textSize = 14f
            setTextColor(0xFF142D66.toInt())
            setPadding(14.dp, 11.dp, 14.dp, 11.dp)
            maxWidth = 280.dp
            background = GradientDrawable().apply {
                setColor(if (isUser) 0xFFDDF5F2.toInt() else Color.WHITE)
                cornerRadius = 16.dp.toFloat()
                if (!isUser) setStroke(1.dp, 0xFFE3EAF5.toInt())
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
                                "text" to response,
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
                            setChatStatus("Gemini couldn't reply: ${error.localizedMessage ?: "Check Firebase AI Logic setup and connection."}")
                            updateChatControls()
                        }
                    }
                }
            }
            .addOnFailureListener { error ->
                chatSending = false
                setChatStatus("Couldn't save your message: ${error.localizedMessage ?: "Check your connection and chat rules."}")
                updateChatControls()
            }
    }

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

    private fun transactionForm(record: QueryDocumentSnapshot?) {
        page = "Transaction form"
        heading.text = if (record == null) "Add Transaction" else "Edit Transaction"
        content.removeAllViews()
        pageBanner("SPENDING ACTIVITY", "Record a transaction", "Add the amount and details below")
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
        setTextColor(0xFF142D66.toInt())
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
            destructive -> 0xFFAB4242.toInt()
            else -> 0xFF174A9A.toInt()
        })
        background = GradientDrawable().apply {
            setColor(when {
                primary -> 0xFF18AFA8.toInt()
                destructive -> 0xFFFBEDEE.toInt()
                else -> 0xFFE5EEFC.toInt()
            })
            cornerRadius = 16.dp.toFloat()
        }
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun input(hintText: String, value: String, type: Int): EditText = EditText(this).apply {
        hint = hintText
        setText(value)
        inputType = type
        textSize = 15f
        setTextColor(0xFF142D66.toInt())
        setHintTextColor(0xFF71809B.toInt())
        setPadding(16.dp, 0, 16.dp, 0)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = 14.dp.toFloat()
            setStroke(1.dp, 0xFFDCE5F1.toInt())
        }
        content.addView(this, LinearLayout.LayoutParams(-1, 56.dp).apply { topMargin = 12.dp })
    }

    private fun parseCents(raw: String): Long? = try {
        BigDecimal(raw.trim()).movePointRight(2).longValueExact()
    } catch (_: Exception) { null }

    private fun money(cents: Long): String = "KES ${currency.format(BigDecimal.valueOf(cents, 2))}"
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}