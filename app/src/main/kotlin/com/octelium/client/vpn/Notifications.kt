package com.octelium.client.vpn

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.octelium.client.MainActivity
import com.octelium.client.R
import com.octelium.client.core.domain.toInstant
import octelium.api.client.daemon.v1.Daemonv1.ConnectionStatus
import octelium.api.client.daemon.v1.Daemonv1.DomainState

object Notifications {
    const val CHANNEL_VPN = "vpn"
    const val CHANNEL_ALERTS = "alerts"

    const val ID_VPN = 1
    const val ID_ALERT = 2

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_VPN,
                context.getString(R.string.notification_channel_vpn),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_vpn_description)
                setShowBadge(false)
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.notification_channel_alerts),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notification_channel_alerts_description)
            },
        )
    }

    fun getMainActivityIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun getVpnNotification(context: Context, state: DomainState?): Notification {
        val connState = state?.connection?.state

        val title = when (connState) {
            ConnectionStatus.State.CONNECTED -> "Connected to ${state.domain}"
            ConnectionStatus.State.CONNECTING -> "Connecting to ${state.domain}"
            ConnectionStatus.State.RECONNECTING -> "Reconnecting to ${state.domain}"
            ConnectionStatus.State.DISCONNECTING -> "Disconnecting from ${state.domain}"
            else -> "Starting Octelium"
        }

        val ret = NotificationCompat.Builder(context, CHANNEL_VPN)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentIntent(getMainActivityIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (connState == ConnectionStatus.State.CONNECTED) {
            toInstant(state.connection.connectedAt)?.let {
                ret.setShowWhen(true).setUsesChronometer(true).setWhen(it.toEpochMilli())
            }
        }

        if (state != null && connState != ConnectionStatus.State.DISCONNECTING) {
            ret.addAction(
                R.drawable.ic_plug,
                "Disconnect",
                PendingIntent.getService(
                    context,
                    1,
                    Intent(context, OcteliumVpnService::class.java).setAction(OcteliumVpnService.ACTION_DISCONNECT),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }

        return ret.build()
    }

    fun notifyAlert(context: Context, title: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(getMainActivityIntent(context))
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(ID_ALERT, notification)
    }
}
