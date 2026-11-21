package com.shirorun

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.shirorun.core.AntiDetect
import com.shirorun.core.FlagManager
import com.shirorun.core.Injector
import com.shirorun.core.ScriptRepository
import kotlin.concurrent.thread

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var repo: ScriptRepository
    private var btn: View? = null
    private var panel: View? = null
    private var logView: TextView? = null
    private var nameField: EditText? = null
    private var srcField: EditText? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        repo = ScriptRepository(this)
        startFg()
        thread { Setup.provision(this) }
        addButton()
    }

    private fun startFg() {
        val ch = NotificationChannel("ov", "Overlay", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        val n = android.app.Notification.Builder(this, "ov")
            .setContentTitle("ShiroRun")
            .setContentText("Botón flotante activo")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun bg(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }

    // ---------- botón ----------
    private fun addButton() {
        val b = TextView(this).apply {
            text = "S"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            background = bg(Color.parseColor("#E60E0E12"), 28)
            setOnLongClickListener { stopSelf(); true }
        }

        val lp = WindowManager.LayoutParams(
            dp(56), dp(56),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12); y = dp(240)
        }

        var dx = 0; var dy = 0; var moved = false
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dx = lp.x - e.rawX.toInt(); dy = lp.y - e.rawY.toInt(); moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX.toInt() + dx - lp.x) > dp(6) ||
                        Math.abs(e.rawY.toInt() + dy - lp.y) > dp(6)) moved = true
                    lp.x = e.rawX.toInt() + dx; lp.y = e.rawY.toInt() + dy
                    wm.updateViewLayout(b, lp)
                }
                MotionEvent.ACTION_UP -> if (!moved) togglePanel()
            }
            true
        }

        wm.addView(b, lp)
        btn = b
    }

    private fun togglePanel() {
        if (panel != null) removePanel() else addPanel()
    }

    // ---------- panel ----------
    private fun addPanel() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(Color.parseColor("#F2101016"), 16)
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = "ShiroRun"; setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(this).apply {
            text = "✕"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { removePanel() }
        })
        root.addView(head)

        nameField = EditText(this).apply {
            hint = "nombre"; setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setText("script1")
        }
        root.addView(nameField)

        srcField = EditText(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(android.graphics.Typeface.MONOSPACE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText("print('hola desde ShiroRun')")
            minLines = 5
        }
        root.addView(srcField, LinearLayout.LayoutParams(-1, 0, 3f))

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(mkBtn("Inyectar", "#1E6E3C") {
            log(if (Injector.attach()) "Inyectado" else "Fallo (root?)")
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row1.addView(mkBtn("Run", "#7A1E1E") {
            val n = nameField?.text?.toString().orEmpty()
            val s = srcField?.text?.toString().orEmpty()
            if (Injector.run(n, s)) {
                Thread.sleep(500)
                log("rc=" + Injector.lastResult(n))
            } else log("no se pudo encolar")
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row1.addView(mkBtn("Guardar", "#2A2A3A") {
            repo.save(nameField?.text?.toString().orEmpty(), srcField?.text?.toString().orEmpty())
            log("guardado")
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        FlagManager.presets.keys.forEach { k ->
            row2.addView(mkBtn(k, "#23233A") {
                log(if (FlagManager.apply(FlagManager.presets[k]!!)) "flags $k" else "error")
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        row2.addView(mkBtn("Limpiar", "#3A2323") {
            AntiDetect.kill(); AntiDetect.wipe(); log("limpiado")
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row2)

        logView = TextView(this).apply {
            setTextColor(Color.parseColor("#9AA0A6")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            text = "Listo"
        }
        root.addView(logView, LinearLayout.LayoutParams(-1, 0, 1f))

        val lp = WindowManager.LayoutParams(
            dp(340), dp(520),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        wm.addView(root, lp)
        panel = root
        btn?.visibility = View.GONE
    }

    private fun mkBtn(t: String, color: String, cb: () -> Unit) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(6), dp(10), dp(6), dp(10))
        background = bg(Color.parseColor(color), 8)
        setOnClickListener { cb() }
    }

    private fun log(s: String) {
        logView?.post { logView?.text = s }
    }

    private fun removePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        btn?.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        removePanel()
        btn?.let { runCatching { wm.removeView(it) } }
        super.onDestroy()
    }
}
