package com.thunder.free

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Switch
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var overlayParams: WindowManager.LayoutParams
    private lateinit var canvasView: SurfaceView

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var espEnabled = false
    private var aimbotEnabled = false

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        overlayView = LayoutInflater.from(this).inflate(R.layout.floating_panel, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        // Draggable
        overlayView.findViewById<View>(R.id.drag_bar).setOnTouchListener(object : View.OnTouchListener {
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = overlayParams.x
                        initialY = overlayParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        overlayParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        overlayParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(overlayView, overlayParams)
                        return true
                    }
                }
                return false
            }
        })

        // Toggles
        overlayView.findViewById<Switch>(R.id.toggle_esp).setOnCheckedChangeListener { _, isChecked ->
            espEnabled = isChecked
            ConfigManager.saveBoolean("esp", isChecked)
            if (isChecked) startCaptureAndDetection()
        }

        overlayView.findViewById<Switch>(R.id.toggle_aimbot).setOnCheckedChangeListener { _, isChecked ->
            aimbotEnabled = isChecked
            ConfigManager.saveBoolean("aimbot", isChecked)
        }

        overlayView.findViewById<Switch>(R.id.toggle_esp).isChecked = ConfigManager.getBoolean("esp", false)
        overlayView.findViewById<Switch>(R.id.toggle_aimbot).isChecked = ConfigManager.getBoolean("aimbot", false)

        windowManager.addView(overlayView, overlayParams)

        // Add canvas for ESP drawing
        canvasView = SurfaceView(this)
        val canvasParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        windowManager.addView(canvasView, canvasParams)
    }

    private fun startCaptureAndDetection() {
        // Start MediaProjection (assume permission granted from activity)
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // mediaProjection = mgr.getMediaProjection(Activity.RESULT_OK, data) → call from activity result

        // For demo assume we have mediaProjection instance
        // mediaProjection?.let { mp ->
        //     val metrics = resources.displayMetrics
        //     imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        //     virtualDisplay = mp.createVirtualDisplay(
        //         "FreeThunderCapture",
        //         metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
        //         DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        //         imageReader?.surface, null, null
        //     )
        //
        //     imageReader?.setOnImageAvailableListener({ reader ->
        //         executor.execute {
        //             processFrame(reader.acquireLatestImage())
        //         }
        //     }, handler)
        // }
    }

    private fun processFrame(image: Image?) {
        image ?: return

        val planes = image.planes
        val buffer = planes[0].buffer
        val rowStride = planes[0].rowStride
        val width = image.width
        val height = image.height

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)

        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        // Convert to HSV for red detection (enemy heads often red in FF)
        val hsv = Mat()
        Imgproc.cvtColor(mat, hsv, Imgproc.COLOR_RGBA2HSV)

        val lowerRed = Scalar(0.0, 100.0, 100.0)
        val upperRed = Scalar(10.0, 255.0, 255.0)
        val mask1 = Mat()
        Core.inRange(hsv, lowerRed, upperRed, mask1)

        val lowerRed2 = Scalar(160.0, 100.0, 100.0)
        val upperRed2 = Scalar(180.0, 255.0, 255.0)
        val mask2 = Mat()
        Core.inRange(hsv, lowerRed2, upperRed2, mask2)

        Core.bitwise_or(mask1, mask2, mask1)

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(mask1, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val paint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        val canvas = Canvas(bitmap)
        for (contour in contours) {
            val rect = Imgproc.boundingRect(contour)
            if (rect.width > 30 && rect.height > 80) { // filter small noise
                canvas.drawRect(rect.x.toFloat(), rect.y.toFloat(),
                    (rect.x + rect.width).toFloat(), (rect.y + rect.height).toFloat(), paint)

                // Aimbot - target head (top 20% of box)
                if (aimbotEnabled && AccessibilityClickService.instance != null) {
                    val headX = rect.x + rect.width / 2f
                    val headY = rect.y + (rect.height * 0.15f) // head area
                    AccessibilityClickService.instance?.clickAt(headX, headY)
                }
            }
        }

        // Draw on overlay canvas (simplified)
        // In real app use SurfaceHolder lockCanvas() → draw on overlay
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        windowManager.removeView(overlayView)
        // Stop capture
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }
}
