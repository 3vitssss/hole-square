package io.github.threevits.holesquare

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable

/**
 * 内置形状。
 *
 * ── 加新内置形状的方法 ──
 *
 * 往枚举里加一项，然后在 [buildDrawable] 里给出对应的 Drawable 就行 ——
 * 界面上的单选框是从 `HoleShape.entries` 生成的，会自动出现。
 * 参数也不用管：`HoleConfig.defaultParamsFor` 的 `else` 分支会给它一套默认值。
 *
 * 自定义项（用户自己粘 SVG、自己命名、可增删的）不走这个枚举，
 * 它们在 [HoleConfig.customs] 里，见 [drawableFor]。
 *
 * ── 关于物理孔那个躲不掉的圆 ──
 *
 * 挖孔是面板上的一个物理圆洞，永远黑着，擦不掉。
 * 所以最终你看到的是「形状 ∪ 这个圆」。
 *
 * 画多大由 [ShapeParams.sizePx] 控制（可在 app 里调）。
 * **方形可以直接用来量真实孔径**：把它调大到黑边刚好消失，那个边长就是孔径 ——
 * 而且因为形状和圆同心，方形包住圆的条件就是边长 ≥ 直径。
 *
 * 顶点在上的等腰三角形要包住同样大小的圆，得比方形大一倍
 * （边长 S 满足 S/2 ≥ r，即 S ≥ 圆直径）。
 */
enum class HoleShape(val id: String, val label: String) {
    SQUARE("square", "方形"),
    TRIANGLE("triangle", "三角形");

    fun buildDrawable(): Drawable = when (this) {
        SQUARE -> ColorDrawable(Color.BLACK)
        TRIANGLE -> TriangleDrawable(Color.BLACK)
    }

    companion object {
        val DEFAULT = SQUARE

        /** 认不出来就返回 null —— 调用方自己决定怎么兜底 */
        fun fromIdOrNull(id: String?): HoleShape? = entries.firstOrNull { it.id == id }

        fun fromId(id: String?): HoleShape = fromIdOrNull(id) ?: DEFAULT
    }
}

/**
 * 按配置取要画的 Drawable。
 *
 * 选中自定义项时把它的 SVG 源码交给 [SvgDrawable] 解析；解析不出来（源码是空的、
 * 或者格式不认识）就退回默认形状 —— 宁可画个方块，也不要屏幕上那块直接空掉。
 */
fun drawableFor(config: HoleConfig): Drawable {
    config.selectedCustom?.let { custom ->
        return SvgDrawable.parse(custom.svg) ?: HoleShape.DEFAULT.buildDrawable()
    }
    return (config.builtin ?: HoleShape.DEFAULT).buildDrawable()
}

/**
 * 顶点在上边中点、底边贴下边的等腰三角形，画满整个 bounds。
 *
 * 用 Path 而不是 ShapeDrawable / GradientDrawable —— 那两个画不出三角形。
 */
class TriangleDrawable(color: Int) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val path = Path()

    /**
     * 注意：Drawable.draw() 的约定是"画在 getBounds() 里"，但 canvas **不会**
     * 自动平移到 bounds 的原点 —— 框架自带的 ColorDrawable 内部自己做了这个偏移。
     * 手写 Drawable 必须自己加上 bounds.left / bounds.top，否则尺寸对、位置却钉在 (0,0)。
     */
    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val left = b.left.toFloat()
        val top = b.top.toFloat()
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        path.reset()
        path.moveTo(left + w / 2f, top)
        path.lineTo(left + w, top + h)
        path.lineTo(left, top + h)
        path.close()
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.OPAQUE"))
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
