package com.threevits.holesquare

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import org.xmlpull.v1.XmlPullParser
import kotlin.math.max
import kotlin.math.sqrt

/**
 * SVG 渐变的收集与还原。
 *
 * ── 为什么这个功能值得单独写 ──
 *
 * 「从全黑淡到全透明」在 SVG 里就是一条 alpha 渐变：
 *
 * ```xml
 * <defs><linearGradient id="fade" x1="0" y1="0" x2="0" y2="1">
 *   <stop offset="0%"   stop-color="#000" stop-opacity="1"/>
 *   <stop offset="100%" stop-color="#000" stop-opacity="0"/>
 * </linearGradient></defs>
 * <rect fill="url(#fade)" .../>
 * ```
 *
 * Android 的 [LinearGradient] / [RadialGradient] 直接吃带 alpha 的 ARGB，
 * 所以「淡到透明」不需要任何特殊处理，颜色里带上 alpha 就行。
 *
 * ── 坐标怎么落 ──
 *
 * `gradientUnits` 默认是 `objectBoundingBox` —— 也就是渐变坐标是**相对于图形外框的比例**
 * （0~1），不是绝对坐标。所以必须等拿到图形本身、算出它的 bounds 之后才能建 Shader，
 * 这也是为什么渐变要在 [SvgDrawable] 的 addPart 阶段才解析、而不是读属性的时候。
 */
internal object SvgGradient {

    const val TAG_LINEAR = "lineargradient"
    const val TAG_RADIAL = "radialgradient"
    const val TAG_STOP = "stop"

    /** 一个色标。offset 已归一化到 0..1。 */
    data class Stop(val offset: Float, val color: Int)

    /** 收集阶段的渐变定义。attrs 保留原始字符串，等建 Shader 时再解释。 */
    class Def(
        val radial: Boolean,
        val attrs: Map<String, String>,
        val stops: MutableList<Stop> = ArrayList(),
        var href: String? = null
    )

    /** 把一个元素上的属性读成 map（关掉命名空间处理后用不上命名空间）。 */
    fun attrMap(parser: XmlPullParser): Map<String, String> {
        val map = HashMap<String, String>()
        for (i in 0 until parser.attributeCount) {
            map[parser.getAttributeName(i).lowercase()] = parser.getAttributeValue(i)
        }
        parser.getAttributeValue(null, "style")?.let { inline ->
            inline.split(';').forEach { decl ->
                val idx = decl.indexOf(':')
                if (idx > 0) {
                    map[decl.substring(0, idx).trim().lowercase()] = decl.substring(idx + 1).trim()
                }
            }
        }
        return map
    }

    fun readStop(parser: XmlPullParser): Stop {
        val attrs = attrMap(parser)
        val offset = parseOffset(attrs["offset"])
        val rgb = parseStopColor(attrs["stop-color"])
        val alpha = attrs["stop-opacity"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
        return Stop(offset, rgb.withAlpha(alpha))
    }

    /** `0.5`、`50%` 都合法，统一归一化到 0..1。 */
    private fun parseOffset(raw: String?): Float {
        val s = raw?.trim() ?: return 0f
        return if (s.endsWith("%")) {
            ((s.dropLast(1).toFloatOrNull() ?: 0f) / 100f).coerceIn(0f, 1f)
        } else {
            (s.toFloatOrNull() ?: 0f).coerceIn(0f, 1f)
        }
    }

    private fun parseStopColor(raw: String?): Int {
        // 复用主解析器那套颜色规则。返回 null 表示 none —— 色标上不该出现，
        // 真出现了就当成全透明，比当黑色更符合直觉
        return parseColor(raw ?: "") ?: Color.TRANSPARENT
    }

    /**
     * 建 Shader。
     *
     * @param defs  收集到的全部渐变（按 id）
     * @param id    要用的那个 id
     * @param bounds 使用该渐变的图形外框（**已应用变换之后**的），objectBoundingBox 靠它换算
     * @param matrix 当前累积的变换，userSpaceOnUse 的坐标要跟着走
     */
    fun build(
        defs: Map<String, Def>,
        id: String,
        bounds: RectF,
        matrix: Matrix,
        alpha: Float = 1f
    ): Shader? {
        val def = resolve(defs, id, HashSet()) ?: return null
        val stops = def.stops
        if (stops.isEmpty()) return null

        val a = alpha.coerceIn(0f, 1f)
        if (stops.size == 1) {
            // 单个色标：Android 的 Shader 至少要两个颜色，用首尾同色的线性渐变模拟纯色，
            // 这样调用方不用区分"纯色"和"渐变"两条路
            val only = stops[0].color.withAlpha(a)
            return LinearGradient(
                0f, 0f, 1f, 0f, intArrayOf(only, only), null, Shader.TileMode.CLAMP
            )
        }

        val colors = stops.map { it.color.withAlpha(a) }.toIntArray()
        // Android 要求位置单调不减，乱序的 SVG 得先捋顺（顺手去重，完全重合的位置会踩 assert）
        val positions = normalise(stops.map { it.offset })

        val tile = when (def.attrs["spreadmethod"]?.trim()?.lowercase()) {
            "reflect" -> Shader.TileMode.MIRROR
            "repeat" -> Shader.TileMode.REPEAT
            else -> Shader.TileMode.CLAMP
        }

        val userSpace = def.attrs["gradientunits"]?.trim()
            ?.equals("userSpaceOnUse", ignoreCase = true) == true

        val shader = if (def.radial) {
            buildRadial(def, colors, positions, tile, bounds, userSpace, matrix)
        } else {
            buildLinear(def, colors, positions, tile, bounds, userSpace, matrix)
        } ?: return null

        // gradientTransform 直接挂在 Shader 的本地矩阵上，不用自己算坐标
        def.attrs["gradienttransform"]?.let { raw ->
            parseTransform(raw)?.let { shader.setLocalMatrix(it) }
        }
        return shader
    }

    private fun buildLinear(
        def: Def,
        colors: IntArray,
        positions: FloatArray,
        tile: Shader.TileMode,
        bounds: RectF,
        userSpace: Boolean,
        matrix: Matrix
    ): Shader? {
        val a = def.attrs
        // 默认 x1=0% y1=0% x2=100% y2=0% —— 水平从左到右
        var x1 = ratio(a["x1"], 0f)
        var y1 = ratio(a["y1"], 0f)
        var x2 = ratio(a["x2"], 1f)
        var y2 = ratio(a["y2"], 0f)

        if (!userSpace) {
            // objectBoundingBox：把 0~1 的比例映射到图形外框
            x1 = bounds.left + x1 * bounds.width()
            x2 = bounds.left + x2 * bounds.width()
            y1 = bounds.top + y1 * bounds.height()
            y2 = bounds.top + y2 * bounds.height()
        } else {
            // 用户坐标还得跟着图形的变换走
            val pts = floatArrayOf(x1, y1, x2, y2)
            matrix.mapPoints(pts)
            x1 = pts[0]; y1 = pts[1]; x2 = pts[2]; y2 = pts[3]
        }

        return LinearGradient(x1, y1, x2, y2, colors, positions, tile)
    }

    private fun buildRadial(
        def: Def,
        colors: IntArray,
        positions: FloatArray,
        tile: Shader.TileMode,
        bounds: RectF,
        userSpace: Boolean,
        matrix: Matrix
    ): Shader? {
        val a = def.attrs
        // 默认 cx=cy=r=50%
        var cx = ratio(a["cx"], 0.5f)
        var cy = ratio(a["cy"], 0.5f)
        var r = ratio(a["r"], 0.5f)

        if (!userSpace) {
            val w = bounds.width()
            val h = bounds.height()
            cx = bounds.left + cx * w
            cy = bounds.top + cy * h
            // 半径按外框对角线方向的最大尺度换算（SVG 规范用的就是 √((w²+h²)/2)）
            r *= sqrt((w * w + h * h) / 2f)
        } else {
            val pts = floatArrayOf(cx, cy)
            matrix.mapPoints(pts)
            cx = pts[0]
            cy = pts[1]
            r *= averageScale(matrix)
        }

        // 注意：SVG 的 fx / fy（焦点）这里**故意忽略**。
        //
        // 公开 SDK 里带焦点的构造函数长这样：
        //   RadialGradient(float, float, float, float, float, float, long[], float[], TileMode)
        // 九个参数、只吃 long[] 颜色，而且多出来的那个 float 含义不明（源码里是 @hide）。
        // 焦点是极边缘的用法，默认值就等于圆心 —— 为它去猜隐藏参数不值得。
        // 真遇到用了 fx/fy 的图，会退化成同心渐变，视觉差别很小。
        return RadialGradient(cx, cy, r, colors, positions, tile)
    }

    /** 位置必须单调不减；同时把完全重合的项去掉，否则底层 assert 会炸。 */
    private fun normalise(raw: List<Float>): FloatArray {
        val out = FloatArray(raw.size)
        var prev = 0f
        raw.forEachIndexed { i, v ->
            val clamped = max(prev, v.coerceIn(0f, 1f))
            out[i] = clamped
            prev = clamped
        }
        // 去掉尾部与前面重合的位置：保留第一个和最后一个，中间相同的往后压一点
        for (i in 1 until out.size) {
            if (out[i] <= out[i - 1]) out[i] = out[i - 1]
        }
        // 首尾若仍然相同，Android 会画出全平的颜色，这是可接受的退化
        return out
    }

    private fun ratio(raw: String?, fallback: Float): Float {
        val s = raw?.trim() ?: return fallback
        return if (s.endsWith("%")) {
            (s.dropLast(1).toFloatOrNull() ?: fallback * 100f) / 100f
        } else {
            s.toFloatOrNull() ?: fallback
        }
    }

    private fun averageScale(matrix: Matrix): Float {
        val v = FloatArray(9)
        matrix.getValues(v)
        val sx = sqrt(v[0] * v[0] + v[1] * v[1])
        val sy = sqrt(v[3] * v[3] + v[4] * v[4])
        return (sx + sy) / 2f
    }

    /** 支持 `xlink:href="#base"` / `href="#base"`：很多导出器用继承来复用渐变。 */
    private fun resolve(
        defs: Map<String, Def>,
        id: String,
        seen: MutableSet<String>
    ): Def? {
        if (!seen.add(id)) return null   // 循环引用
        val def = defs[id] ?: return null
        val parentId = def.href?.removePrefix("#")?.trim().orEmpty()
        if (parentId.isEmpty()) return def

        val parent = resolve(defs, parentId, seen) ?: return def
        // 子级覆盖父级：没写的属性/色标从父级拿
        return Def(
            radial = def.radial,
            attrs = parent.attrs + def.attrs,
            stops = (if (def.stops.isEmpty()) parent.stops else def.stops).toMutableList(),
            href = null
        )
    }

    /** 只认识 translate / scale / rotate / matrix —— 和图形上的 transform 同一套。 */
    private fun parseTransform(raw: String): Matrix? {
        val m = Matrix()
        var any = false
        Regex("([a-zA-Z]+)\\s*\\(([^)]*)\\)").findAll(raw).forEach { match ->
            val args = match.groupValues[2].split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
            val one = Matrix()
            when (match.groupValues[1].lowercase()) {
                "translate" -> when (args.size) {
                    1 -> one.setTranslate(args[0], 0f)
                    2 -> one.setTranslate(args[0], args[1])
                }

                "scale" -> when (args.size) {
                    1 -> one.setScale(args[0], args[0])
                    2 -> one.setScale(args[0], args[1])
                }

                "rotate" -> when (args.size) {
                    1 -> one.setRotate(args[0])
                    3 -> one.setRotate(args[0], args[1], args[2])
                }

                "matrix" -> if (args.size >= 6) {
                    one.setValues(
                        floatArrayOf(
                            args[0], args[2], args[4],
                            args[1], args[3], args[5],
                            0f, 0f, 1f
                        )
                    )
                }
            }
            m.postConcat(one)
            any = true
        }
        return if (any) m else null
    }
}

/** `url(#id)` / `url("#id")` → `id`；不是渐变引用则返回 null。 */
internal fun parseUrlReference(raw: String): String? {
    val s = raw.trim()
    if (!s.startsWith("url(", ignoreCase = true) || !s.endsWith(")")) return null
    val inner = s.substring(4, s.length - 1).trim().trim('"', '\'', ' ')
    return inner.removePrefix("#").takeIf { it.isNotEmpty() }
}
