package ke.co.pesapath

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthMultiFactorException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.MultiFactorResolver
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.auth.PhoneMultiFactorGenerator
import com.google.firebase.auth.PhoneMultiFactorInfo
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.functions.FirebaseFunctions
import java.util.concurrent.TimeUnit

class AuthActivity : AppCompatActivity() {
    private var registering = false
    private var pendingRegistrationUser: FirebaseUser? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val darkMode = getSharedPreferences("pesapath_preferences", MODE_PRIVATE)
            .getBoolean("dark_mode", true)
        AppCompatDelegate.setDefaultNightMode(
            if (darkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        AppSessionSecurity.requireLoginAfterProcessRestart(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_auth)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.authRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        val fullName = findViewById<EditText>(R.id.fullName)
        val email = findViewById<EditText>(R.id.email)
        val password = findViewById<EditText>(R.id.password)
        val submit = findViewById<Button>(R.id.submit)
        val switchMode = findViewById<Button>(R.id.switchMode)
        val reset = findViewById<Button>(R.id.resetPassword)
        val error = findViewById<TextView>(R.id.authError)

        if (FirebaseApp.getApps(this).isEmpty()) {
            error.text = "Add app/google-services.json from your Firebase project to enable sign in."
            error.visibility = View.VISIBLE
            submit.isEnabled = false
            reset.isEnabled = false
            return
        }
        val auth = FirebaseAuth.getInstance()
        auth.currentUser?.let { user ->
            continueAfterPrimarySignIn(
                user,
                onSignedIn = {
                    startActivity(homeIntent())
                    finish()
                },
                onError = { message ->
                    error.text = message
                    error.visibility = View.VISIBLE
                }
            )
            return
        }
        fun setBusy(busy: Boolean) {
            submit.isEnabled = !busy
            switchMode.isEnabled = !busy && pendingRegistrationUser == null
            reset.isEnabled = !busy
            email.isEnabled = !busy && pendingRegistrationUser == null
            password.isEnabled = !busy && pendingRegistrationUser == null
            fullName.isEnabled = !busy
        }
        fun openHome() {
            startActivity(homeIntent())
            finish()
        }
        fun saveFullName(user: FirebaseUser, name: String) {
            pendingRegistrationUser = user
            user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name).build())
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        pendingRegistrationUser = null
                        openHome()
                    } else {
                        setBusy(false)
                        error.text = "Your account was created, but your name could not be saved. Tap Register to retry."
                        error.visibility = View.VISIBLE
                    }
                }
        }
        switchMode.setOnClickListener {
            registering = !registering
            fullName.visibility = if (registering) View.VISIBLE else View.GONE
            findViewById<TextView>(R.id.authHeading).text = if (registering) "Register" else "Log In"
            submit.text = if (registering) "REGISTER" else "LOGIN"
            switchMode.text = if (registering) "Already have an account? Log in" else "Don't have an account? Register"
            reset.visibility = if (registering) View.GONE else View.VISIBLE
            error.visibility = View.GONE
        }
        submit.setOnClickListener {
            val address = email.text.toString().trim()
            val secret = password.text.toString()
            val name = fullName.text.toString().trim()
            if (registering && name.isBlank()) {
                fullName.error = "Enter your full name"
                fullName.requestFocus()
                return@setOnClickListener
            }
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches() || secret.length < 8) {
                error.text = "Enter a valid email and a password of at least 8 characters."
                error.visibility = View.VISIBLE
                return@setOnClickListener
            }
            error.visibility = View.GONE
            setBusy(true)
            pendingRegistrationUser?.let { user ->
                saveFullName(user, name)
                return@setOnClickListener
            }
            val result = if (registering) auth.createUserWithEmailAndPassword(address, secret)
                else auth.signInWithEmailAndPassword(address, secret)
            result.addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = task.result?.user
                    if (registering && user != null) saveFullName(user, name)
                    else if (user != null) {
                        continueAfterPrimarySignIn(
                            user,
                            onSignedIn = ::openHome,
                            onError = { message ->
                                setBusy(false)
                                error.text = message
                                error.visibility = View.VISIBLE
                            }
                        )
                    } else {
                        setBusy(false)
                        error.text = "Authentication succeeded, but no user account was returned."
                        error.visibility = View.VISIBLE
                    }
                } else {
                    val mfaException = task.exception as? FirebaseAuthMultiFactorException
                    if (mfaException != null) {
                        sendSignInMfaCode(
                            auth,
                            mfaException.resolver,
                            onSignedIn = {
                                val user = auth.currentUser
                                if (user == null) {
                                    setBusy(false)
                                    error.text = "Sign-in could not be completed. Try again."
                                    error.visibility = View.VISIBLE
                                } else {
                                    continueAfterPrimarySignIn(
                                        user,
                                        onSignedIn = ::openHome,
                                        onError = { message ->
                                            setBusy(false)
                                            error.text = message
                                            error.visibility = View.VISIBLE
                                        }
                                    )
                                }
                            },
                            onError = { message ->
                                setBusy(false)
                                error.text = message
                                error.visibility = View.VISIBLE
                            }
                        )
                    } else {
                        setBusy(false)
                        error.text = task.exception?.localizedMessage ?: "Authentication failed. Try again."
                        error.visibility = View.VISIBLE
                    }
                }
            }

        }
        reset.setOnClickListener {
            val address = email.text.toString().trim()
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
                error.text = "Enter your email address first."
                error.visibility = View.VISIBLE
            } else {
                auth.sendPasswordResetEmail(address).addOnCompleteListener(this) { task ->
                    error.text = if (task.isSuccessful) "Password reset email sent." else
                        task.exception?.localizedMessage ?: "Could not send reset email."
                    error.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun continueAfterPrimarySignIn(
        user: FirebaseUser,
        onSignedIn: () -> Unit,
        onError: (String) -> Unit
    ) {
        user.getIdToken(false)
            .addOnSuccessListener(this) { result ->
                if (result.claims["emailMfaEnabled"] != true) {
                    onSignedIn()
                    return@addOnSuccessListener
                }
                FirebaseFunctions.getInstance("us-central1")
                    .getHttpsCallable("beginEmailMfaSignIn")
                    .call()
                    .addOnSuccessListener(this) {
                        showEmailMfaCodePrompt(user, onSignedIn, onError)
                    }
                    .addOnFailureListener(this) { error ->
                        onError(error.localizedMessage ?: "Could not send an email verification code.")
                    }
            }
            .addOnFailureListener(this) { error ->
                onError(error.localizedMessage ?: "Could not verify your account settings.")
            }
    }

    private fun showEmailMfaCodePrompt(
        user: FirebaseUser,
        onSignedIn: () -> Unit,
        onError: (String) -> Unit
    ) {
        val code = EditText(this).apply {
            hint = "6-digit email code"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Email verification")
            .setMessage("Enter the code sent to your account email. It expires in 10 minutes.")
            .setView(code)
            .setNegativeButton("Cancel") { _, _ -> onError("Email verification was cancelled.") }
            .setPositiveButton("Verify", null)
            .create()
        dialog.setOnCancelListener { onError("Email verification was cancelled.") }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = code.text.toString().trim()
                if (value.length != 6 || value.any { !it.isDigit() }) {
                    code.error = "Enter the 6-digit code"
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                FirebaseFunctions.getInstance("us-central1")
                    .getHttpsCallable("verifyEmailMfa")
                    .call(mapOf("code" to value, "purpose" to "sign-in"))
                    .addOnSuccessListener(this) {
                        user.getIdToken(true)
                            .addOnSuccessListener(this) { token ->
                                if (token.claims["emailMfaEnabled"] == true &&
                                    token.claims["emailMfaVerifiedAt"] is Number
                                ) {
                                    dialog.dismiss()
                                    onSignedIn()
                                } else {
                                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                                    code.error = "Verification was not completed. Request a new code."
                                }
                            }
                            .addOnFailureListener(this) { error ->
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                                code.error = error.localizedMessage ?: "Could not refresh your sign-in."
                            }
                    }
                    .addOnFailureListener(this) { error ->
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        code.error = error.localizedMessage ?: "The code could not be verified."
                    }
            }
        }
        dialog.show()
    }

    private fun sendSignInMfaCode(
        auth: FirebaseAuth,
        resolver: MultiFactorResolver,
        onSignedIn: () -> Unit,
        onError: (String) -> Unit
    ) {
        val hint = resolver.hints.filterIsInstance<PhoneMultiFactorInfo>().firstOrNull()
        if (hint == null) {
            onError("This account has no supported SMS verification method. Contact support.")
            return
        }
        var completed = false
        fun resolveCredential(credential: PhoneAuthCredential) {
            if (completed) return
            completed = true
            resolver.resolveSignIn(PhoneMultiFactorGenerator.getAssertion(credential))
                .addOnCompleteListener(this) { result ->
                    if (result.isSuccessful) onSignedIn()
                    else onError(result.exception?.localizedMessage ?: "Could not verify the sign-in code.")
                }
        }
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                resolveCredential(credential)
            }

            override fun onVerificationFailed(exception: com.google.firebase.FirebaseException) {
                if (!completed) {
                    completed = true
                    onError(PhoneVerificationErrors.message(exception))
                }
            }

            override fun onCodeSent(verificationId: String, token: PhoneAuthProvider.ForceResendingToken) {
                if (!completed) {
                    showSmsCodePrompt(
                        "Verify your sign-in",
                        verificationId,
                        ::resolveCredential,
                        onCancelled = {
                            if (!completed) {
                                completed = true
                                onError("Two-factor verification was cancelled.")
                            }
                        }
                    )
                }
            }
        }
        val options = PhoneAuthOptions.newBuilder(auth)
            .setMultiFactorHint(hint)
            .setActivity(this)
            .setCallbacks(callbacks)
            .setTimeout(60L, TimeUnit.SECONDS)
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    private fun showSmsCodePrompt(
        title: String,
        verificationId: String,
        onCredential: (PhoneAuthCredential) -> Unit,
        onCancelled: () -> Unit
    ) {
        val code = EditText(this).apply {
            hint = "6-digit SMS code"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Enter the code sent to your enrolled phone number.")
            .setView(code)
            .setNegativeButton("Cancel") { _, _ -> onCancelled() }
            .setPositiveButton("Verify", null)
            .create()
        dialog.setOnCancelListener { onCancelled() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = code.text.toString().trim()
                if (value.length != 6 || value.any { !it.isDigit() }) {
                    code.error = "Enter the 6-digit code"
                    return@setOnClickListener
                }
                dialog.dismiss()
                onCredential(PhoneAuthProvider.getCredential(verificationId, value))
            }
        }
        dialog.show()
    }

    private fun homeIntent(): Intent = Intent(this, HomeActivity::class.java).apply {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            if (!sharedText.isNullOrBlank()) {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, sharedText.take(4_000))
            }
        }
    }
}