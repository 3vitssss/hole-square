package com.threevits.holesquare

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View

/**
 * 预览：模拟屏幕顶部一条，按**真实比例**画出来。
 *
 * 画面 = 背景 ∪ 物理孔 ∪ 形状。
 *
 * 这里画的「物理孔」用的是 OPPO 属性里的定义（默认 72px）。
 * **注意它未必是真实孔径** —— 实测 72px 的方块包不住屏幕上的那个黑圆，
 * 说明真机上的孔更大。所以量真实大小只能盯着真机调，不能看这个预览。
 * 预览在这里的作用是显示形状本身长什么样、以及它相对屏幕的比例。
 */
class PreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val holeRect = HoleGeometry.readRect()
    private val screenWidthPx = HoleGeometry.screenWidthPx(context)

    /** 属性给出的孔心 —— 偏移量是相对它算的 */
    private val propCenterX = (holeRect[0] + holeRect[2]) / 2f
    private val propCenterY = (holeRect[1] + holeRect[3]) / 2f

    private var config: HoleConfig = HoleConfig.default()
    private var shapeDrawable: Drawable? = null

    /** 屏幕圆角半径，取自设备实际值 */
    private val cornerRadiusPx = 114f

    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EDEDED")
    }
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
    }
    private val rect = RectF()

    fun setConfig(newConfig: HoleConfig) {
        if (newConfig == config) return
        config = newConfig
        shapeDrawable = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())

        // 一个屏幕像素在预览里占多少预览像素
        val scale = width.toFloat() / screenWidthPx

        // ── 背景：屏幕顶部。只圆上面两个角，所以往下多画一个半径让下面被裁掉 ──
        val r = cornerRadiusPx * scale
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height + r, r, r, screenPaint)

        // ── 真实孔：用实测值画，不是属性值 ──
        // 属性写 72，真机上是 82 —— 画属性的那个圆会让预览骗人：
        // 方块明明盖不住真孔，预览里却看着盖住了。
        val circle = HoleGeometry.measuredHoleCircle()
        canvas.drawCircle(circle[0] * scale, circle[1] * scale, circle[2] * scale, holePaint)

        // ── 形状：以（属性孔心 + 手动偏移）为中心，边长 = 配置的 sizePx ──
        val cx = propCenterX + config.offsetXPx
        val cy = propCenterY + config.offsetYPx
        val half = config.sizePx / 2f
        rect.set(
            (cx - half) * scale,
            (cy - half) * scale,
            (cx + half) * scale,
            (cy + half) * scale
        )

        val d = shapeDrawable ?: drawableFor(config).also { shapeDrawable = it }
        d.setBounds(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        d.draw(canvas)
    }
}
