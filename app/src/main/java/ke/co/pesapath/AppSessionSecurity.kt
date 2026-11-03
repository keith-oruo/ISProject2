package ke.co.pesapath

import android.content.Context
import android.os.Process
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth

internal object AppSessionSecurity {
    private const val PREFERENCES_NAME = "pesapath_preferences"
    private const val KEY_PROCESS_ID = "session_process_id"

    fun requireLoginAfterProcessRestart(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val currentProcessId = Process.myPid()
        val previousProcessId = preferences.getInt(KEY_PROCESS_ID, -1)
        if (previousProcessId != currentProcessId && FirebaseApp.getApps(context).isNotEmpty()) {
            FirebaseAuth.getInstance().signOut()
        }
        preferences.edit().putInt(KEY_PROCESS_ID, currentProcessId).apply()
    }
}
