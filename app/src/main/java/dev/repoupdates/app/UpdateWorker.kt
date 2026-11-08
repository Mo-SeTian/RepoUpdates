package dev.repoupdates.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class UpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        (applicationContext as TrackerApplication).tracker.refresh(background = true)
        return Result.success()
    }
}

internal fun scheduleChecks(context: Context, preferences: Preferences) {
    val manager = WorkManager.getInstance(context)
    if (!preferences.background) {
        manager.cancelUniqueWork(workName)
        return
    }
    val hours = preferences.intervalHours.toLong()
    val request = PeriodicWorkRequestBuilder<UpdateWorker>(hours, TimeUnit.HOURS)
        .setInitialDelay(hours, TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    manager.enqueueUniquePeriodicWork(workName, ExistingPeriodicWorkPolicy.UPDATE, request)
}

internal fun createUpdatesChannel(context: Context) {
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(channelId, "仓库新版本", NotificationManager.IMPORTANCE_DEFAULT),
    )
}

internal fun systemNotificationsEnabled(context: Context): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java)
    return manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(channelId)?.importance != NotificationManager.IMPORTANCE_NONE &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
}

internal fun showUpdateNotification(context: Context, repo: RepoName, release: GitHubRelease) {
    if (!systemNotificationsEnabled(context)) return
    val intent = Intent(context, MainActivity::class.java)
        .setData(Uri.parse("repoupdates://release/${release.id}"))
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val notification = Notification.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_update)
        .setContentTitle("${repo.name} ${release.tag}")
        .setContentText(release.summary.ifBlank { repo.fullName + " 发布了新版本" })
        .setContentIntent(pending)
        .setAutoCancel(true)
        .build()
    try {
        context.getSystemService(NotificationManager::class.java).notify(release.id.toString(), 0, notification)
    } catch (_: SecurityException) {
        // The system permission may have changed between the check and delivery.
    }
}

internal fun dismissUpdateNotification(context: Context, id: Long) {
    context.getSystemService(NotificationManager::class.java).cancel(id.toString(), 0)
}

private const val workName = "check-github-releases"
private const val channelId = "repository-releases"
