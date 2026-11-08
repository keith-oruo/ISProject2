package ke.co.pesapath

import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException

internal object PhoneVerificationErrors {
    fun message(error: Exception): String {
        val code = (error as? FirebaseAuthException)?.errorCode.orEmpty()
        val guidance = when {
            error is FirebaseAuthInvalidCredentialsException ||
                code.contains("INVALID_PHONE_NUMBER") ->
                "Firebase rejected the phone number. Enter it in international format, such as +2547XXXXXXXX."

            code.contains("TOO_MANY_REQUESTS") ||
                code.contains("QUOTA_EXCEEDED") ->
                "Firebase has temporarily limited SMS requests. Wait before trying again, or use a Firebase test phone number."

            code.contains("OPERATION_NOT_ALLOWED") ->
                "Phone authentication is disabled. Enable the Phone provider and SMS multi-factor authentication in Firebase Authentication."

            code.contains("APP_NOT_AUTHORIZED") ||
                code.contains("INVALID_APP_CREDENTIAL") ||
                code.contains("CAPTCHA") ||
                code.contains("MISSING_ACTIVITY") ->
                "Firebase could not verify this app. Add this build's SHA-1 and SHA-256 fingerprints to the Android app in Firebase Console, then download the updated google-services.json and rebuild."

            else -> null
        }
        val detail = error.localizedMessage
        return when {
            guidance == null -> detail ?: "Could not verify the phone number."
            detail.isNullOrBlank() -> guidance
            else -> "$guidance\n\nFirebase: $detail"
        }
    }
}
