package ke.co.pesapath

import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.TimeUnit

data class MpesaTransaction(
    val id: String? = null,
    val phone: String,
    val amountCents: Long,
    val status: String,
    val checkoutRequestId: String? = null,
    val merchantRequestId: String? = null,
    val resultCode: String? = null,
    val resultDesc: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class StkResult(
    val transactionId: String,
    val checkoutRequestId: String?,
    val responseDesc: String?
)

class MpesaService {
    private val functions = FirebaseFunctions.getInstance("us-central1")
    private val firestore = FirebaseFirestore.getInstance()

    suspend fun stkPush(phone: String, amountCents: Long): StkResult = withTimeout(30_000) {
        require(amountCents > 0L && amountCents % 100L == 0L) {
            "M-Pesa payments must be a positive whole number of Kenyan shillings."
        }
        withContext(Dispatchers.IO) {
            val result = Tasks.await(
                functions.getHttpsCallable("createMpesaPayment").call(
                    mapOf("phone" to phone, "amountCents" to amountCents)
                ),
                25,
                TimeUnit.SECONDS
            )
            val data = result.data as? Map<*, *>
                ?: throw IllegalStateException("The payment service returned an invalid response.")
            val transactionId = data["transactionId"] as? String
                ?: throw IllegalStateException("The payment service did not return a transaction reference.")
            StkResult(
                transactionId = transactionId,
                checkoutRequestId = data["checkoutRequestId"] as? String,
                responseDesc = data["responseDesc"] as? String
            )
        }
    }

    fun streamTransactions(): Query {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
            ?: throw IllegalStateException("Sign in before viewing M-Pesa transactions.")
        return firestore.collection("users").document(uid)
            .collection("mpesaTransactions")
            .orderBy("createdAt", Query.Direction.DESCENDING)
    }

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"
    }
}
