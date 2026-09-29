package ke.co.pesapath

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth

class AuthActivity : AppCompatActivity() {
    private var registering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_auth)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.authRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
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
        if (auth.currentUser != null) {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            return
        }
        switchMode.setOnClickListener {
            registering = !registering
            findViewById<TextView>(R.id.authHeading).text = if (registering) "Register" else "Log In"
            submit.text = if (registering) "REGISTER" else "LOGIN"
            switchMode.text = if (registering) "Already have an account? Log in" else "Don't have an account? Register"
            reset.visibility = if (registering) View.GONE else View.VISIBLE
            error.visibility = View.GONE
        }
        submit.setOnClickListener {
            val address = email.text.toString().trim()
            val secret = password.text.toString()
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches() || secret.length < 8) {
                error.text = "Enter a valid email and a password of at least 8 characters."
                error.visibility = View.VISIBLE
                return@setOnClickListener
            }
            submit.isEnabled = false
            val result = if (registering) auth.createUserWithEmailAndPassword(address, secret)
                else auth.signInWithEmailAndPassword(address, secret)
            result.addOnCompleteListener(this) { task ->
                submit.isEnabled = true
                if (task.isSuccessful) {
                    startActivity(Intent(this, HomeActivity::class.java))
                    finish()
                } else {
                    error.text = task.exception?.localizedMessage ?: "Authentication failed. Try again."
                    error.visibility = View.VISIBLE
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
}