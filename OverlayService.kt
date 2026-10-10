package com.free.smcict

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.text.Html
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var bubble: TextView
    private lateinit var panel: TextView
    private lateinit var p: WindowManager.LayoutParams
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var running = true
    private var last = ""

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("s", "Signals", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, "s").setContentTitle("XAU SMC+ICT is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info).build())
        build()
        Thread {
            while (running) {
                try {
                    val r = Engine.analyze(Feed.candles(15), Feed.candles(60))
                    ui.post { render(r) }
                } catch (e: Exception) { ui.post { bubble.text = "ERR" } }
                try { Thread.sleep(30000) } catch (e: InterruptedException) { }
            }
        }.start()
    }

    private fun build() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        bubble = TextView(this).apply {
            text = "XAU"; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
            setPadding(36, 28, 36, 28)
            background = GradientDrawable().apply { cornerRadius = 80f; setColor(Color.GRAY) }
        }
        panel = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f; setPadding(32, 24, 32, 24)
            setBackgroundColor(0xEE101010.toInt()); visibility = View.GONE
        }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(bubble); addView(panel) }
        p = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 300 }
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        bubble.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = p.x; sy = p.y; tx = e.rawX; ty = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt(); val dy = (e.rawY - ty).toInt()
                    if (Math.abs(dx) + Math.abs(dy) > 12) moved = true
                    p.x = sx + dx; p.y = sy + dy; wm.updateViewLayout(root, p)
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    panel.visibility = if (panel.visibility == View.GONE) View.VISIBLE else View.GONE
                    wm.updateViewLayout(root, p)
                }
            }
            true
        }
        wm.addView(root, p)
    }

    private fun f(x: Double) = String.format("%.1f", x)

    private fun line(s: Sig?): String {
        if (s == null) return "<font color='#999999'>WAIT</font>"
        val c = if (s.dir > 0) "#2ecc71" else "#ff5252"
        return "<font color='$c'><b>${if (s.dir > 0) "BUY" else "SELL"}</b> from ${f(s.entry)} and take profit at ${f(s.tp)}</font>" +
            "<br><small>stop loss ${f(s.sl)} &middot; confidence ${s.score}%</small>"
    }

    private fun render(r: Result) {
        val c = r.comb
        bubble.text = if (c == null) "WAIT" else if (c.dir > 0) "BUY" else "SELL"
        (bubble.background as GradientDrawable).setColor(if (c == null) Color.GRAY else if (c.dir > 0) 0xFF1E9E5A.toInt() else 0xFFD63B3B.toInt())
        panel.text = Html.fromHtml("<b>SMC</b><br>${line(r.smc)}<br><br><b>ICT</b><br>${line(r.ict)}<br><br><b>COMBINED</b><br>${line(c)}" +
            (if (r.note.isNotEmpty()) "<br><small>${r.note}</small>" else "") +
            "<br><br><small>XAUUSDT ${f(r.price)}</small>", Html.FROM_HTML_MODE_LEGACY)
        val sig = if (c == null) "" else "${c.dir}:${f(c.entry)}"
        if (sig != "" && sig != last) {
            panel.visibility = View.VISIBLE
            @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        last = sig
        wm.updateViewLayout(root, p)
    }

    override fun onDestroy() {
        running = false
        try { wm.removeView(root) } catch (e: Exception) { }
        super.onDestroy()
    }
}
