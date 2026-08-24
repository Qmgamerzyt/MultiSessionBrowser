package com.app.browser.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.app.browser.R

class FloatingBallService : Service() {
    
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var initialX = 0f
    private var initialY = 0f
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isLongPress = false
    private var longPressRunnable: Runnable? = null
    
    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "floating_ball_channel"
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        createFloatingBall()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating Ball",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows floating ball for toolbar access"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Floating Ball Active")
            .setContentText("Tap to restore toolbar")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
    
    private fun createFloatingBall() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }
        
        floatingView = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            setBackgroundResource(R.drawable.floating_ball_background)
            setPadding(8, 8, 8, 8)
            setOnTouchListener { v, event -> onTouch(event) }
            setOnClickListener { onBallTap() }
            setOnLongClickListener { onBallLongPress(); true }
        }
        
        windowManager?.addView(floatingView, params)
    }
    
    private fun onTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = floatingView?.x ?: 0f
                initialY = floatingView?.y ?: 0f
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isLongPress = false
                
                // Start long press detection
                longPressRunnable = Runnable { isLongPress = true }
                floatingView?.postDelayed(longPressRunnable!!, 500)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isLongPress) {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    floatingView?.x = initialX + dx
                    floatingView?.y = initialY + dy
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                longPressRunnable?.let { floatingView?.removeCallbacks(it) }
                if (!isLongPress) {
                    // Snap to edge
                    snapToEdge()
                }
                true
            }
            else -> false
        }
    }
    
    private fun snapToEdge() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val screenWidth = display.width
        val screenHeight = display.height
        
        val viewWidth = floatingView?.width ?: 0
        val viewHeight = floatingView?.height ?: 0
        
        val currentX = floatingView?.x ?: 0f
        val currentY = floatingView?.y ?: 0f
        
        // Determine nearest edge
        val leftDist = currentX
        val rightDist = screenWidth - currentX - viewWidth
        val topDist = currentY
        val bottomDist = screenHeight - currentY - viewHeight
        
        var newX = currentX
        var newY = currentY
        
        // Snap horizontally
        if (leftDist < rightDist) {
            newX = 0f
        } else {
            newX = (screenWidth - viewWidth).toFloat()
        }
        
        // Keep vertical position but clamp
        newY = newY.coerceIn(0f, (screenHeight - viewHeight).toFloat())
        
        // Animate to new position
        floatingView?.animate()
            .x(newX)
            .y(newY)
            .setDuration(200)
            .start()
        
        // Update layout params
        val params = floatingView?.layoutParams as? WindowManager.LayoutParams
        params?.x = newX.toInt()
        params?.y = newY.toInt()
        params?.let { windowManager.updateViewLayout(floatingView!!, it) }
    }
    
    private fun onBallTap() {
        // Send broadcast to restore toolbar
        val intent = Intent("com.app.browser.RESTORE_TOOLBAR")
        sendBroadcast(intent)
        stopSelf()
    }
    
    private fun onBallLongPress() {
        isLongPress = true
        // Show radial menu
        showRadialMenu()
    }
    
    private fun showRadialMenu() {
        // TODO: Implement radial menu with options:
        // New Tab, Switch Session, Run Scripts, Tabs, Home
        // For now, just send broadcast
        val intent = Intent("com.app.browser.SHOW_RADIAL_MENU")
        sendBroadcast(intent)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        floatingView?.let { view ->
            windowManager?.removeView(view)
            floatingView = null
        }
        longPressRunnable?.let { floatingView?.removeCallbacks(it) }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}

// Drawable for floating ball background - create in res/drawable/floating_ball_background.xml