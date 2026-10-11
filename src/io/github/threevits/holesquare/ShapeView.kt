package io.github.threevits.holesquare

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.view.View
import de.robv.android.xposed.XposedBridge

/**
 * 只画一个形状的 View。
 *
 * ── 为什么不用 view.background = drawable ──
 *
 * 一开始是那么写的，结果换形状时**画面纹丝不动**：日志明明打了「形状已切到「五角星」」，
 * 屏幕上还是方块。背景 Drawable 在换的时候不一定会把窗口重新画出来。
 *
 * 改成自己 onDraw 之后，invalidate() 走的是一条确定会重绘的路径，
 * 不再依赖背景 Drawable 那套内部状态机。
 */
class ShapeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var config: HoleConfig = HoleConfig.default()
        set(value) {
            if (field == value) return
            field = value
            shapeDrawable = null
            invalidate()
        }

    /**
     * 横屏时置 false：**什么都不画**。
     *
     * 不 removeView 是刻意的 —— 旋转过程中把窗口摘了再加回来会撞上
     * 「ViewRoot 正在重建」那一类竞态。而这个窗口本来就是 TRANSLUCENT、
     * 除了形状什么都不画（onDraw 开头还 CLEAR 一遍），所以「不画」就等于全透明，
     * 屏幕上跟窗口不存在一模一样。
     */
    var showShape: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private var shapeDrawable: android.graphics.drawable.Drawable? = null

    /** 前几次 onDraw 打日志，用来确认窗口到底有没有重绘 */
    private var drawCount = 0

    init {
        // 纯 View 子类，确保框架不会跳过 onDraw
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        // 每帧必须先清干净。
        //
        // 这个 View 没有背景，所以框架不认为它是 opaque，硬件渲染器**会复用上一帧的缓冲**。
        // 不清的话，新形状是画在旧的方块上面，看着还是方块 —— 这个坑踩过：
        // 开机首发是默认的方块，几秒后同步成五角星，结果画面纹丝不动。
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // 横屏：清完就走，窗口存在但全透明
        if (!showShape) return

        val d = shapeDrawable ?: drawableFor(config).also { shapeDrawable = it }
        // 自己画就得自己设 bounds —— View 只会给背景 Drawable 设，不会给"我手动画的"设
        d.setBounds(0, 0, width, height)
        d.draw(canvas)

        if (drawCount < 8) {
            drawCount++
            XposedBridge.log(
                "[${Tag.NAME}] onDraw #$drawCount ${config.label} ${width}x$height"
            )
        }
    }
}
