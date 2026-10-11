package com.threevits.holesquare

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import kotlin.math.min

/**
 * 一段 SVG 源码 → 可以画出来的 [Drawable]。
 *
 * ── 支持范围 ──
 *
 * 元素：`svg` `g` `path` `rect` `circle` `ellipse` `line` `polyline` `polygon`
 * 属性：`d` `viewBox` `fill` `stroke` `stroke-width` `fill-opacity` `stroke-opacity`
 *      `opacity` `stroke-linecap` `stroke-linejoin` `style`（内联声明，优先级高于同名属性）
 * 颜色：`none` `#RGB` `#RRGGBB` `#RRGGBBAA` `rgb()` `rgba()` `transparent` `currentColor`
 *      和一小撮常见颜色名
 * 变换：`g` 上的 `translate` `scale` `rotate` `matrix`（烘焙进 Path，不靠 Canvas 状态）
 *
 * **不支持**：渐变（取第一个色标当纯色）、`<use>` / `<defs>` / `<clipPath>` / 滤镜、
 * CSS `<style>` 块和 class 选择器、`<text>`、动画。
 * 遇到不认识的元素直接跳过，不会让整张图挂掉。
 *
 * ── 坐标怎么对上窗口 ──
 *
 * 所有路径都按 SVG 自己的坐标系存，绘制时统一按 viewBox 缩放到 bounds 里，
 * **保持长宽比、居中**。所以非正方形的图标不会变形，但会留白 ——
 * 留白处是透明的，那个黑圆会从那里露出来，这点在预览里能直接看到。
 */
class SvgDrawable private constructor(
    private val viewWidth: Float,
    private val viewHeight: Float,
    private val viewX: Float,
    private val viewY: Float,
    private val parts: List<Part>
) : Drawable() {

    private class Part(
        val path: Path,
        /** 纯色填充。与 [fillShader] 二选一，两个都是 null 表示不填充 */
        val fill: Int?,
        /** 渐变填充。非 null 时盖过 [fill] */
        val fillShader: Shader?,
        val stroke: Int?,
        val strokeShader: Shader?,
        val strokeWidth: Float,
        val cap: Paint.Cap,
        val join: Paint.Join
    )

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun draw(canvas: Canvas) {
        if (parts.isEmpty()) return
        val b = bounds
        if (b.isEmpty) return

        val vw = if (viewWidth > 0f) viewWidth else b.width().toFloat()
        val vh = if (viewHeight > 0f) viewHeight else b.height().toFloat()

        // 保持长宽比，缩放到 bounds 内并居中
        val scale = min(b.width() / vw, b.height() / vh)
        val dx = b.left + (b.width() - vw * scale) / 2f
        val dy = b.top + (b.height() - vh * scale) / 2f

        val save = canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        // viewBox 的左下角未必是 0,0，这里抵消掉
        canvas.translate(-viewX, -viewY)

        parts.forEach { part ->
            // 有 shader 时 Paint 的颜色会被忽略，所以这里给个黑色占位即可
            if (part.fillShader != null || part.fill != null) {
                fillPaint.shader = part.fillShader
                fillPaint.color = part.fill ?: Color.BLACK
                canvas.drawPath(part.path, fillPaint)
            }
            if (part.strokeShader != null || part.stroke != null) {
                strokePaint.shader = part.strokeShader
                strokePaint.color = part.stroke ?: Color.BLACK
                strokePaint.strokeWidth = part.strokeWidth
                strokePaint.strokeCap = part.cap
                strokePaint.strokeJoin = part.join
                canvas.drawPath(part.path, strokePaint)
            }
        }

        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {

        /** 解析失败、或者图里没有任何可画的东西时返回 null。 */
        fun parse(svg: String): SvgDrawable? = try {
            Parser(svg).run()
        } catch (_: Throwable) {
            null
        }

        /** 把 `<!DOCTYPE ...>` 去掉：KXmlParser 开着 PROCESS_DOCDECL 时会去解析外部 DTD，可能联网。 */
        private val DOCTYPE = Regex("<!DOCTYPE[^>\\[]*(\\[[^\\]]*])?[^>]*>", RegexOption.IGNORE_CASE)
    }

    private class Parser(private val source: String) {

        private val parts = ArrayList<Part>()

        private var viewX = 0f
        private var viewY = 0f
        private var viewW = 0f
        private var viewH = 0f

        private var style = SvgStyle()
        private var matrix = Matrix()
        private val styleStack = ArrayDeque<SvgStyle>()
        private val matrixStack = ArrayDeque<Matrix>()

        /** 收集到的渐变定义，按 id 存 */
        private val gradients = HashMap<String, SvgGradient.Def>()

        /** 正在收集色标的那个渐变。停止标签是它的子元素，靠这个变量认领 */
        private var buildingGradient: SvgGradient.Def? = null

        fun run(): SvgDrawable? {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(DOCTYPE.replace(source, "")))

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> onStart(parser)
                    XmlPullParser.END_TAG -> onEnd(parser)
                }
                event = parser.next()
            }

            if (parts.isEmpty()) return null

            // viewBox 没写的话，退而用所有图形的实际外框
            if (viewW <= 0f || viewH <= 0f) {
                val all = RectF()
                val one = RectF()
                parts.forEach {
                    it.path.computeBounds(one, false)
                    all.union(one)
                }
                viewX = all.left; viewY = all.top
                viewW = all.width(); viewH = all.height()
            }
            if (viewW <= 0f || viewH <= 0f) return null

            return SvgDrawable(viewW, viewH, viewX, viewY, parts)
        }

        /** 关掉命名空间处理后 `name` 可能带前缀（`svg:path`），只取冒号后面那段。 */
        private fun tagOf(parser: XmlPullParser): String =
            parser.name.substringAfterLast(':').lowercase()

        private fun onStart(parser: XmlPullParser) {
            val tag = tagOf(parser)
            when (tag) {
                "svg" -> {
                    pushScope()
                    style = style.merge(parser)
                    readViewBox(parser)
                }

                "g" -> {
                    pushScope()
                    style = style.merge(parser)
                    applyTransform(parser.getAttributeValue(null, "transform"))
                }

                "path" -> {
                    val d = parser.getAttributeValue(null, "d") ?: return
                    val p = SvgPath.parse(d)
                    if (!p.isEmpty) addPart(p, style.merge(parser))
                }

                "rect" -> {
                    val r = rectPath(parser) ?: return
                    addPart(r, style.merge(parser))
                }

                "circle" -> {
                    val cx = num(parser, "cx", 0f)
                    val cy = num(parser, "cy", 0f)
                    val r = num(parser, "r", 0f)
                    if (r > 0f) {
                        val p = Path().apply { addOval(cx - r, cy - r, cx + r, cy + r, Path.Direction.CW) }
                        addPart(p, style.merge(parser))
                    }
                }

                "ellipse" -> {
                    val cx = num(parser, "cx", 0f)
                    val cy = num(parser, "cy", 0f)
                    val rx = num(parser, "rx", 0f)
                    val ry = num(parser, "ry", 0f)
                    if (rx > 0f && ry > 0f) {
                        val p = Path().apply { addOval(cx - rx, cy - ry, cx + rx, cy + ry, Path.Direction.CW) }
                        addPart(p, style.merge(parser))
                    }
                }

                "line" -> {
                    val p = Path().apply {
                        moveTo(num(parser, "x1", 0f), num(parser, "y1", 0f))
                        lineTo(num(parser, "x2", 0f), num(parser, "y2", 0f))
                    }
                    // 线没有面积，填充对它没意义，强制不填
                    addPart(p, style.merge(parser).copy(fill = null))
                }

                "polyline", "polygon" -> {
                    val pts = points(parser)
                    if (pts.size >= 4) {
                        val p = Path().apply {
                            moveTo(pts[0], pts[1])
                            var i = 2
                            while (i + 1 < pts.size) {
                                lineTo(pts[i], pts[i + 1]); i += 2
                            }
                            if (tag == "polygon") close()
                        }
                        addPart(p, style.merge(parser))
                    }
                }

                SvgGradient.TAG_LINEAR, SvgGradient.TAG_RADIAL -> {
                    val attrs = SvgGradient.attrMap(parser)
                    val def = SvgGradient.Def(
                        radial = tag == SvgGradient.TAG_RADIAL,
                        attrs = attrs
                    )
                    def.href = attrs["xlink:href"] ?: attrs["href"]
                    attrs["id"]?.let { gradients[it] = def }
                    buildingGradient = def
                }

                SvgGradient.TAG_STOP -> {
                    buildingGradient?.stops?.add(SvgGradient.readStop(parser))
                }

                else -> Unit   // defs / use / text / 未知元素，跳过
            }
        }

        private fun onEnd(parser: XmlPullParser) {
            when (tagOf(parser)) {
                "svg", "g" -> {
                    if (styleStack.isNotEmpty()) style = styleStack.removeFirst()
                    if (matrixStack.isNotEmpty()) matrix = matrixStack.removeFirst()
                }

                SvgGradient.TAG_LINEAR, SvgGradient.TAG_RADIAL -> buildingGradient = null
            }
        }

        private fun pushScope() {
            styleStack.addFirst(style)
            matrixStack.addFirst(Matrix(matrix))
        }

        private fun addPart(path: Path, elementStyle: SvgStyle) {
            // 变换烘焙进路径本身，绘制时就不用再管 Canvas 状态了
            if (!matrix.isIdentity) path.transform(matrix)

            // 渐变必须等拿到路径外框之后才能建 —— gradientUnits 默认是 objectBoundingBox，
            // 坐标是"相对外框的比例"，没有 bounds 就无从换算
            val bounds = RectF()
            if (elementStyle.fillRef != null || elementStyle.strokeRef != null) {
                path.computeBounds(bounds, true)
            }

            parts.add(
                Part(
                    path = path,
                    fill = elementStyle.fill,
                    fillShader = elementStyle.fillRef?.let {
                        SvgGradient.build(gradients, it, bounds, matrix, elementStyle.opacity)
                    },
                    stroke = elementStyle.stroke,
                    strokeShader = elementStyle.strokeRef?.let {
                        SvgGradient.build(gradients, it, bounds, matrix, elementStyle.opacity)
                    },
                    strokeWidth = elementStyle.strokeWidth,
                    cap = elementStyle.cap,
                    join = elementStyle.join
                )
            )
        }

        private fun readViewBox(parser: XmlPullParser) {
            val vb = parser.getAttributeValue(null, "viewBox")
            if (vb != null) {
                val n = vb.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
                if (n.size >= 4 && n[2] > 0f && n[3] > 0f) {
                    viewX = n[0]; viewY = n[1]; viewW = n[2]; viewH = n[3]
                    return
                }
            }
            // 没有 viewBox 就退而用 width/height（去掉 px 之类的单位）
            val w = parser.getAttributeValue(null, "width")?.let { parseLength(it) } ?: 0f
            val h = parser.getAttributeValue(null, "height")?.let { parseLength(it) } ?: 0f
            if (w > 0f && h > 0f) {
                viewW = w; viewH = h
            }
        }

        private fun rectPath(parser: XmlPullParser): Path? {
            val w = num(parser, "width", 0f)
            val h = num(parser, "height", 0f)
            if (w <= 0f || h <= 0f) return null
            val x = num(parser, "x", 0f)
            val y = num(parser, "y", 0f)
            val rx = num(parser, "rx", 0f)
            val ry = num(parser, "ry", rx)
            return Path().apply {
                if (rx > 0f || ry > 0f) {
                    addRoundRect(RectF(x, y, x + w, y + h), rx, ry, Path.Direction.CW)
                } else {
                    addRect(x, y, x + w, y + h, Path.Direction.CW)
                }
            }
        }

        private fun points(parser: XmlPullParser): FloatArray {
            val raw = parser.getAttributeValue(null, "points") ?: return FloatArray(0)
            val list = raw.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
            return list.toFloatArray()
        }

        private fun num(parser: XmlPullParser, name: String, fallback: Float): Float =
            parser.getAttributeValue(null, name)?.let { parseLength(it) } ?: fallback

        private fun parseLength(raw: String): Float? =
            raw.trim().removeSuffix("px").toFloatOrNull()

        /**
         * `transform` 只支持 translate / scale / rotate / matrix 这四种，
         * 而且按**书写顺序**依次左乘 —— 这是 SVG 的规定（变换串按顺序作用于元素）。
         * 出现 skew 之类不认识的函数就跳过那一项，不做特殊处理。
         */
        private fun applyTransform(raw: String?) {
            if (raw.isNullOrBlank()) return
            Regex("([a-zA-Z]+)\\s*\\(([^)]*)\\)").findAll(raw).forEach { m ->
                val fn = m.groupValues[1].lowercase()
                val args = m.groupValues[2]
                    .split(Regex("[\\s,]+"))
                    .mapNotNull { it.toFloatOrNull() }
                val mtx = Matrix()
                when (fn) {
                    "translate" -> when (args.size) {
                        1 -> mtx.setTranslate(args[0], 0f)
                        2 -> mtx.setTranslate(args[0], args[1])
                    }

                    "scale" -> when (args.size) {
                        1 -> mtx.setScale(args[0], args[0])
                        2 -> mtx.setScale(args[0], args[1])
                    }

                    "rotate" -> when (args.size) {
                        1 -> mtx.setRotate(args[0])
                        3 -> mtx.setRotate(args[0], args[1], args[2])
                    }

                    "matrix" -> if (args.size >= 6) {
                        mtx.setValues(
                            floatArrayOf(
                                args[0], args[2], args[4],
                                args[1], args[3], args[5],
                                0f, 0f, 1f
                            )
                        )
                    }
                }
                matrix.postConcat(mtx)
            }
        }
    }

    /** 一个元素上累积下来的绘制属性。子元素从父元素继承。 */
    private data class SvgStyle(
        val fill: Int? = Color.BLACK,
        /** `fill="url(#id)"` 引用的渐变 id */
        val fillRef: String? = null,
        val stroke: Int? = null,
        val strokeRef: String? = null,
        val strokeWidth: Float = 1f,
        val cap: Paint.Cap = Paint.Cap.BUTT,
        val join: Paint.Join = Paint.Join.MITER,
        /** 沿继承链累乘下来的 opacity。纯色会直接折进颜色，渐变得靠它 */
        val opacity: Float = 1f
    ) {
        /**
         * 把当前元素的属性合并进来。同名属性直接覆盖（这就是 SVG 的继承规则）。
         * 另外还要读 `style="fill:red;stroke-width:2"` 内联声明 ——
         * Figma / Inkscape 导出的 SVG 大多把属性塞在这里而不是写成独立属性。
         */
        fun merge(parser: XmlPullParser): SvgStyle {
            val props = HashMap<String, String>()
            for (i in 0 until parser.attributeCount) {
                props[parser.getAttributeName(i).lowercase()] = parser.getAttributeValue(i)
            }
            parser.getAttributeValue(null, "style")?.let { inline ->
                inline.split(';').forEach { decl ->
                    val idx = decl.indexOf(':')
                    if (idx > 0) {
                        props[decl.substring(0, idx).trim().lowercase()] =
                            decl.substring(idx + 1).trim()
                    }
                }
            }

            var next = this

            // opacity 作用于整个元素，填充和描边一起淡。
            // 纯色直接折进颜色通道；渐变没法折，所以另记一份累乘值给 Shader 用。
            props["opacity"]?.toFloatOrNull()?.let { op ->
                val clamped = op.coerceIn(0f, 1f)
                next = next.copy(
                    opacity = next.opacity * clamped,
                    fill = next.fill?.withAlpha(clamped),
                    stroke = next.stroke?.withAlpha(clamped)
                )
            }

            // fill / stroke 可能是纯色，也可能是 url(#渐变)。引用渐变时把纯色清掉，
            // 否则两套都会生效（渐变优先，但纯色留着会让"不填充"的语义变乱）
            props["fill"]?.let { raw ->
                val ref = parseUrlReference(raw)
                next = if (ref != null) {
                    next.copy(fillRef = ref, fill = null)
                } else {
                    next.copy(fill = parseColor(raw), fillRef = null)
                }
            }
            props["stroke"]?.let { raw ->
                val ref = parseUrlReference(raw)
                next = if (ref != null) {
                    next.copy(strokeRef = ref, stroke = null)
                } else {
                    next.copy(stroke = parseColor(raw), strokeRef = null)
                }
            }
            props["fill-opacity"]?.toFloatOrNull()?.let { a ->
                next = next.copy(fill = next.fill?.withAlpha(a))
            }
            props["stroke-opacity"]?.toFloatOrNull()?.let { a ->
                next = next.copy(stroke = next.stroke?.withAlpha(a))
            }
            props["stroke-width"]?.let { raw ->
                raw.trim().removeSuffix("px").toFloatOrNull()?.let { next = next.copy(strokeWidth = it) }
            }
            props["stroke-linecap"]?.let { raw ->
                next = next.copy(
                    cap = when (raw.trim().lowercase()) {
                        "round" -> Paint.Cap.ROUND
                        "square" -> Paint.Cap.SQUARE
                        else -> Paint.Cap.BUTT
                    }
                )
            }
            props["stroke-linejoin"]?.let { raw ->
                next = next.copy(
                    join = when (raw.trim().lowercase()) {
                        "round" -> Paint.Join.ROUND
                        "bevel" -> Paint.Join.BEVEL
                        else -> Paint.Join.MITER
                    }
                )
            }
            return next
        }
    }
}

/** 把一个浮点透明度乘进颜色的 alpha 通道。 */
internal fun Int.withAlpha(factor: Float): Int {
    val a = (Color.alpha(this) * factor.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
    return Color.argb(a, Color.red(this), Color.green(this), Color.blue(this))
}

internal val NAMED_COLORS = mapOf(
    "black" to 0xFF000000.toInt(),
    "white" to 0xFFFFFFFF.toInt(),
    "red" to 0xFFFF0000.toInt(),
    "green" to 0xFF008000.toInt(),
    "blue" to 0xFF0000FF.toInt(),
    "yellow" to 0xFFFFFF00.toInt(),
    "gray" to 0xFF808080.toInt(),
    "grey" to 0xFF808080.toInt(),
    "silver" to 0xFFC0C0C0.toInt(),
    "orange" to 0xFFFFA500.toInt(),
    "purple" to 0xFF800080.toInt(),
    "pink" to 0xFFFFC0CB.toInt(),
    "brown" to 0xFFA52A2A.toInt(),
    "cyan" to 0xFF00FFFF.toInt(),
    "magenta" to 0xFFFF00FF.toInt(),
    "lime" to 0xFF00FF00.toInt(),
    "navy" to 0xFF000080.toInt(),
    "teal" to 0xFF008080.toInt(),
    "gold" to 0xFFFFD700.toInt()
)

/**
 * SVG 颜色 → ARGB。**返回 null 表示"不画"**（`none`），
 * 这是 `fill="none"` 那种描边型图标能正确渲染的关键。
 */
internal fun parseColor(raw: String): Int? {
    val s = raw.trim().lowercase()
    if (s.isEmpty() || s == "none" || s == "transparent") return null
    if (s == "currentcolor") return Color.BLACK   // 没有 CSS 上下文，按黑色处理

    if (s.startsWith("#")) {
        val hex = s.substring(1)
        return try {
            when (hex.length) {
                3 -> {
                    val r = hex[0].digitToInt(16) * 17
                    val g = hex[1].digitToInt(16) * 17
                    val b = hex[2].digitToInt(16) * 17
                    Color.argb(255, r, g, b)
                }

                6 -> Color.parseColor("#$hex")
                // 有些导出器写 8 位，后两位是 alpha（注意不是 CSS 的 #RRGGBBAA 顺序之外的写法）
                8 -> {
                    val rgb = Color.parseColor("#${hex.substring(0, 6)}")
                    val a = hex.substring(6).toInt(16)
                    rgb.withAlpha(a / 255f)
                }

                else -> Color.BLACK
            }
        } catch (_: Throwable) {
            Color.BLACK
        }
    }

    if (s.startsWith("rgb")) {
        val nums = s.substringAfter('(').substringBefore(')')
            .split(Regex("[\\s,]+"))
            .filter { it.isNotEmpty() }
        if (nums.size >= 3) {
            val r = channel(nums[0]); val g = channel(nums[1]); val b = channel(nums[2])
            val a = if (nums.size >= 4) (nums[3].toFloatOrNull() ?: 1f) else 1f
            return Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), r, g, b)
        }
        return Color.BLACK
    }

    return NAMED_COLORS[s] ?: Color.BLACK
}

/** rgb() 的通道值可以是 0-255 整数，也可以是 0%-100% 百分比。 */
internal fun channel(raw: String): Int = if (raw.endsWith("%")) {
    ((raw.dropLast(1).toFloatOrNull() ?: 0f) * 2.55f).toInt().coerceIn(0, 255)
} else {
    (raw.toFloatOrNull() ?: 0f).toInt().coerceIn(0, 255)
}
