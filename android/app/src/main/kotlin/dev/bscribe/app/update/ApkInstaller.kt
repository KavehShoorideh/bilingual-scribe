package dev.bscribe.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import java.io.File

/**
 * Hands a verified APK to the system package installer.
 *
 * Uses the [PackageInstaller] session API rather than an ACTION_VIEW intent,
 * so no FileProvider and no world-readable copy of the APK is needed. Android
 * enforces that an update carries the same signing key as the installed app,
 * which our release pipeline guarantees.
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"

    /**
     * @return null on success (the system takes over from here), or a reason
     *   the install could not be started.
     */
    fun install(context: Context, apk: File): String? {
        if (!apk.isFile || apk.length() == 0L) return "Update file is missing."

        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply {
                setAppPackageName(context.packageName)
            }

            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }

                val intent = Intent(context, InstallResultReceiver::class.java)
                    .setAction(InstallResultReceiver.ACTION_INSTALL_RESULT)
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    // MUTABLE is required: the system fills in status extras.
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                session.commit(pending.intentSender)
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "install failed", e)
            e.message ?: "Could not start the installer."
        }
    }
}

/**
 * Receives the session result. An unprivileged app installing an APK always
 * gets STATUS_PENDING_USER_ACTION first — the confirmation dialog only appears
 * if we launch the intent the system hands back here.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    // We are outside an Activity context here, so this must
                    // start its own task.
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                } else {
                    Log.e(TAG, "pending user action with no confirmation intent")
                }
            }
            PackageInstaller.STATUS_SUCCESS ->
                Log.i(TAG, "update installed")
            else ->
                Log.e(
                    TAG,
                    "install failed: status=$status " +
                        intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                )
        }
    }

    companion object {
        private const val TAG = "InstallResult"
        const val ACTION_INSTALL_RESULT = "dev.bscribe.app.action.INSTALL_RESULT"
    }
}
