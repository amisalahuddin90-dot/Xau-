package com.free.smcict

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        fun btn(t: String, a: () -> Unit) = l.addView(Button(this).apply { text = t; setOnClickListener { a() } })
        l.addView(TextView(this).apply {
            text = "XAU/USDT  SMC + ICT signal bubble\n\nFree. No account. Uses public Binance/Bybit price data.\nThis is a decision aid, not financial advice. Always use the stop loss."
            textSize = 16f
        })
        btn("1. Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("2. Start floating bubble") {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
            if (Settings.canDrawOverlays(this)) startForegroundService(Intent(this, OverlayService::class.java))
        }
        btn("Stop bubble") { stopService(Intent(this, OverlayService::class.java)) }
        setContentView(l)
    }
}
