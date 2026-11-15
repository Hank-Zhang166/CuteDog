package com.cutedog.app.pet

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import com.cutedog.app.MainActivity
import com.cutedog.app.R

/** New Android versions restrict boot-time foreground services: offer an explicit restart. */
class PetBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!PetSettings(context).boot) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("pet_restore", "重启后恢复陪伴", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 210, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(210, Notification.Builder(context, "pet_restore").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${PetSettings(context).name}准备好陪你了").setContentText("点击打开 CuteDog，恢复桌宠陪伴")
            .setContentIntent(open).setAutoCancel(true).build())
    }
}
