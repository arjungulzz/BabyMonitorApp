package apadev232228.babymonitor.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class RoiOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paintBorder = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }

    private val paintCorner = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
    }

    private val paintBackground = Paint().apply {
        color = Color.parseColor("#80000000") // Semi-transparent black outside
        style = Paint.Style.FILL
    }
    
    // The normalized ROI rect (0.0 to 1.0)
    var roiRectNorm = RectF(0.4f, 0.4f, 0.6f, 0.6f) // Small center square
    
    // Callback for ROI changes (real-time)
    var onRoiChanged: ((RectF) -> Unit)? = null
    
    // The actual drawing rect in view coordinates
    private val roiRect = RectF()
    
    private var isEditable = false
    private var activeCorner = -1 // -1: None, 0: TopLeft, 1: TopRight, 2: BotRight, 3: BotLeft, 4: Center
    private val cornerSize = 40f
    
    private var lastX = 0f
    private var lastY = 0f

    fun setEditable(editable: Boolean) {
        isEditable = editable
        invalidate()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateRectFromNorm()
    }

    private fun updateRectFromNorm() {
        roiRect.set(
            roiRectNorm.left * width,
            roiRectNorm.top * height,
            roiRectNorm.right * width,
            roiRectNorm.bottom * height
        )
    }

    private fun updateNormFromRect() {
        roiRectNorm.set(
            roiRect.left / width,
            roiRect.top / height,
            roiRect.right / width,
            roiRect.bottom / height
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (!isEditable) return // Hidden by default

        // Draw darkened background outside ROI
        // Top
        canvas.drawRect(0f, 0f, width.toFloat(), roiRect.top, paintBackground)
        // Bottom
        canvas.drawRect(0f, roiRect.bottom, width.toFloat(), height.toFloat(), paintBackground)
        // Left
        canvas.drawRect(0f, roiRect.top, roiRect.left, roiRect.bottom, paintBackground)
        // Right
        canvas.drawRect(roiRect.right, roiRect.top, width.toFloat(), roiRect.bottom, paintBackground)

        // Draw Border
        canvas.drawRect(roiRect, paintBorder)

        // Draw Handles
        canvas.drawCircle(roiRect.left, roiRect.top, cornerSize, paintCorner)
        canvas.drawCircle(roiRect.right, roiRect.top, cornerSize, paintCorner)
        canvas.drawCircle(roiRect.right, roiRect.bottom, cornerSize, paintCorner)
        canvas.drawCircle(roiRect.left, roiRect.bottom, cornerSize, paintCorner)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEditable) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                activeCorner = getActiveCorner(event.x, event.y)
                return activeCorner != -1
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeCorner != -1) {
                    when (activeCorner) {
                        0 -> { // Top Left
                            roiRect.left = event.x.coerceIn(0f, roiRect.right - 100)
                            roiRect.top = event.y.coerceIn(0f, roiRect.bottom - 100)
                        }
                        1 -> { // Top Right
                            roiRect.right = event.x.coerceIn(roiRect.left + 100, width.toFloat())
                            roiRect.top = event.y.coerceIn(0f, roiRect.bottom - 100)
                        }
                        2 -> { // Bot Right
                            roiRect.right = event.x.coerceIn(roiRect.left + 100, width.toFloat())
                            roiRect.bottom = event.y.coerceIn(roiRect.top + 100, height.toFloat())
                        }
                        3 -> { // Bot Left
                            roiRect.left = event.x.coerceIn(0f, roiRect.right - 100)
                            roiRect.bottom = event.y.coerceIn(roiRect.top + 100, height.toFloat())
                        }
                        4 -> { // Center (Move)
                            val dx = event.x - lastX
                            val dy = event.y - lastY
                            roiRect.offset(dx, dy)
                            
                            // Clamp to screen
                            if (roiRect.left < 0) roiRect.offset(-roiRect.left, 0f)
                            if (roiRect.top < 0) roiRect.offset(0f, -roiRect.top)
                            if (roiRect.right > width) roiRect.offset(width - roiRect.right, 0f)
                            if (roiRect.bottom > height) roiRect.offset(0f, height - roiRect.bottom)
                        }
                    }
                    updateNormFromRect()
                    onRoiChanged?.invoke(roiRectNorm)
                    invalidate()
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                activeCorner = -1
                onRoiChanged?.invoke(roiRectNorm) // Invoke callback on ACTION_UP as well
            }
        }
        return true
    }

    private fun getActiveCorner(x: Float, y: Float): Int {
        val touchSize = cornerSize * 2.5f
        
        // Check corners
        if (dist(x, y, roiRect.left, roiRect.top) < touchSize) return 0
        if (dist(x, y, roiRect.right, roiRect.top) < touchSize) return 1
        if (dist(x, y, roiRect.right, roiRect.bottom) < touchSize) return 2
        if (dist(x, y, roiRect.left, roiRect.bottom) < touchSize) return 3
        
        // Check Center
        if (roiRect.contains(x, y)) return 4
        
        return -1
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return kotlin.math.hypot(x1 - x2, y1 - y2)
    }
}
