package io.github.threevits.holesquare

import android.graphics.Path
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * SVG 的 `d` 属性 → [android.graphics.Path]。
 *
 * ── 为什么不借 framework 的现成实现 ──
 *
 * 有两条近路，都堵死了：
 *
 * 1. `Drawable.createFromXml(res, Xml.newPullParser())` —— 看起来能直接把一段 XML 字符串
 *    变成 VectorDrawable，但 `Xml.asAttributeSet()` 要求 parser 实现 `AttributeSet`。
 *    实测设备上的 `KXmlParser` 只 implements `XmlPullParser` / `Closeable`，会抛异常。
 * 2. 自己实现 `AttributeSet` 再走 `createFromXmlInner` —— `Resources.obtainAttributes()`
 *    依赖 `getAttributeNameResource()` 返回 `com.android.internal.R.styleable` 的数值 ID，
 *    那些 ID 是隐藏的，这条路是个泥潭。
 *
 * 所以自己写。全公开 API，app 进程和 SystemUI 进程行为完全一致。
 *
 * ── 支持范围 ──
 *
 * 命令：M/m L/l H/h V/v C/c S/s Q/q T/t A/a Z/z，含隐式重复（M 后续坐标按 L 处理）。
 * 数字可以任意分隔：空格、逗号、或直接贴着写负数（`10-5`）。
 */
internal object SvgPath {

    private const val PI = Math.PI
    private const val TWO_PI = PI * 2
    private const val EPS = 1e-6

    fun parse(d: String): Path {
        val path = Path()
        val sc = Scanner(d)

        // cmd 是"本轮要执行的命令"；M 处理完会改写成 L，于是后续坐标对自动变成 lineto
        var cmd = ' '
        // prevCmd 记录"上一轮真正执行过的命令"，供 S/T 判断能不能延续控制点
        var prevCmd = ' '

        var curX = 0f; var curY = 0f
        var startX = 0f; var startY = 0f
        var lastCubicX = 0f; var lastCubicY = 0f
        var lastQuadX = 0f; var lastQuadY = 0f

        try {
            while (true) {
                sc.skipSeparators()
                if (!sc.hasMore()) break

                if (sc.peek().isLetter()) {
                    cmd = sc.next()
                } else if (cmd == 'Z' || cmd == 'z' || cmd == ' ') {
                    // 数字前面没有命令字母，无从解释（Z 后面也不该直接跟数字）
                    break
                }

                val thisCmd = cmd
                val rel = thisCmd.isLowerCase()

                when (thisCmd) {
                    'M', 'm' -> {
                        val x = sc.number(); val y = sc.number()
                        curX = if (rel) curX + x else x
                        curY = if (rel) curY + y else y
                        path.moveTo(curX, curY)
                        startX = curX; startY = curY
                        // SVG 规范：M 后面继续跟坐标对的话，那些按 lineto 处理
                        cmd = if (rel) 'l' else 'L'
                    }

                    'L', 'l' -> {
                        val x = sc.number(); val y = sc.number()
                        curX = if (rel) curX + x else x
                        curY = if (rel) curY + y else y
                        path.lineTo(curX, curY)
                    }

                    'H', 'h' -> {
                        val x = sc.number()
                        curX = if (rel) curX + x else x
                        path.lineTo(curX, curY)
                    }

                    'V', 'v' -> {
                        val y = sc.number()
                        curY = if (rel) curY + y else y
                        path.lineTo(curX, curY)
                    }

                    'C', 'c' -> {
                        val c1x = sc.number(); val c1y = sc.number()
                        val c2x = sc.number(); val c2y = sc.number()
                        val ex = sc.number(); val ey = sc.number()
                        val a1x = if (rel) curX + c1x else c1x
                        val a1y = if (rel) curY + c1y else c1y
                        val a2x = if (rel) curX + c2x else c2x
                        val a2y = if (rel) curY + c2y else c2y
                        val endX = if (rel) curX + ex else ex
                        val endY = if (rel) curY + ey else ey
                        path.cubicTo(a1x, a1y, a2x, a2y, endX, endY)
                        lastCubicX = a2x; lastCubicY = a2y
                        curX = endX; curY = endY
                    }

                    'S', 's' -> {
                        val c2x = sc.number(); val c2y = sc.number()
                        val ex = sc.number(); val ey = sc.number()
                        // 只有上一条是 C/S 时才有"上一个控制点"可言，否则退化成当前点
                        val smooth = prevCmd == 'C' || prevCmd == 'c' ||
                            prevCmd == 'S' || prevCmd == 's'
                        val a1x = if (smooth) 2 * curX - lastCubicX else curX
                        val a1y = if (smooth) 2 * curY - lastCubicY else curY
                        val a2x = if (rel) curX + c2x else c2x
                        val a2y = if (rel) curY + c2y else c2y
                        val endX = if (rel) curX + ex else ex
                        val endY = if (rel) curY + ey else ey
                        path.cubicTo(a1x, a1y, a2x, a2y, endX, endY)
                        lastCubicX = a2x; lastCubicY = a2y
                        curX = endX; curY = endY
                    }

                    'Q', 'q' -> {
                        val c1x = sc.number(); val c1y = sc.number()
                        val ex = sc.number(); val ey = sc.number()
                        val a1x = if (rel) curX + c1x else c1x
                        val a1y = if (rel) curY + c1y else c1y
                        val endX = if (rel) curX + ex else ex
                        val endY = if (rel) curY + ey else ey
                        path.quadTo(a1x, a1y, endX, endY)
                        lastQuadX = a1x; lastQuadY = a1y
                        curX = endX; curY = endY
                    }

                    'T', 't' -> {
                        val ex = sc.number(); val ey = sc.number()
                        val smooth = prevCmd == 'Q' || prevCmd == 'q' ||
                            prevCmd == 'T' || prevCmd == 't'
                        val a1x = if (smooth) 2 * curX - lastQuadX else curX
                        val a1y = if (smooth) 2 * curY - lastQuadY else curY
                        val endX = if (rel) curX + ex else ex
                        val endY = if (rel) curY + ey else ey
                        path.quadTo(a1x, a1y, endX, endY)
                        lastQuadX = a1x; lastQuadY = a1y
                        curX = endX; curY = endY
                    }

                    'A', 'a' -> {
                        val rx = sc.number(); val ry = sc.number()
                        val rot = sc.number()
                        val largeArc = sc.flag()
                        val sweep = sc.flag()
                        val ex = sc.number(); val ey = sc.number()
                        val endX = if (rel) curX + ex else ex
                        val endY = if (rel) curY + ey else ey
                        arcTo(path, curX, curY, rx, ry, rot, largeArc, sweep, endX, endY)
                        curX = endX; curY = endY
                    }

                    'Z', 'z' -> {
                        path.close()
                        curX = startX; curY = startY
                    }

                    else -> return path   // 不认识的命令，放弃
                }

                // 平滑控制点只在同族命令之间延续，一换族就作废
                if (thisCmd != 'C' && thisCmd != 'c' && thisCmd != 'S' && thisCmd != 's') {
                    lastCubicX = curX; lastCubicY = curY
                }
                if (thisCmd != 'Q' && thisCmd != 'q' && thisCmd != 'T' && thisCmd != 't') {
                    lastQuadX = curX; lastQuadY = curY
                }
                prevCmd = thisCmd
            }
        } catch (_: Throwable) {
            // 中途解析失败就把已经画出来的部分返回，不要让整个图案消失
        }
        return path
    }

    /**
     * 椭圆弧 → 三次贝塞尔。
     *
     * SVG 用「端点参数化」描述弧（起点、终点、两个半径、旋转角、两个标志位），
     * 而 Path 只能画贝塞尔。先按 SVG 实现附录取出圆心和起止角，再拆成 ≤90° 的小段逼近。
     */
    private fun arcTo(
        path: Path,
        x0: Float, y0: Float,
        rxIn: Float, ryIn: Float,
        angleDeg: Float,
        largeArc: Boolean, sweep: Boolean,
        x1: Float, y1: Float
    ) {
        if (abs(x0 - x1) < EPS && abs(y0 - y1) < EPS) return

        var rx = abs(rxIn)
        var ry = abs(ryIn)
        if (rx < EPS || ry < EPS) {
            path.lineTo(x1, y1)   // 半径退化，规范说当直线处理
            return
        }

        val phi = Math.toRadians(angleDeg.toDouble())
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)

        val dx = (x0 - x1) / 2.0
        val dy = (y0 - y1) / 2.0
        val x1p = cosPhi * dx + sinPhi * dy
        val y1p = -sinPhi * dx + cosPhi * dy

        // 半径给得太小时规范要求等比放大到刚好够
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1) {
            val s = sqrt(lambda)
            rx = (rx * s).toFloat()
            ry = (ry * s).toFloat()
        }

        val rx2 = (rx * rx).toDouble()
        val ry2 = (ry * ry).toDouble()
        val x1p2 = x1p * x1p
        val y1p2 = y1p * y1p

        var num = rx2 * ry2 - rx2 * y1p2 - ry2 * x1p2
        if (num < 0) num = 0.0
        val den = rx2 * y1p2 + ry2 * x1p2
        var coef = if (den < EPS) 0.0 else sqrt(num / den)
        // largeArc 与 sweep 相同则取负号
        if (largeArc == sweep) coef = -coef

        val cxp = coef * (rx * y1p / ry)
        val cyp = coef * (-ry * x1p / rx)

        val cx = cosPhi * cxp - sinPhi * cyp + (x0 + x1) / 2.0
        val cy = sinPhi * cxp + cosPhi * cyp + (y0 + y1) / 2.0

        val ux = (x1p - cxp) / rx
        val uy = (y1p - cyp) / ry
        val vx = (-x1p - cxp) / rx
        val vy = (-y1p - cyp) / ry

        val theta1 = vectorAngle(1.0, 0.0, ux, uy)
        var delta = vectorAngle(ux, uy, vx, vy)
        if (!sweep && delta > 0) delta -= TWO_PI
        else if (sweep && delta < 0) delta += TWO_PI

        // 每段不超过 90°，贝塞尔才贴得住弧
        val segments = ceil(abs(delta) / (PI / 2)).toInt().coerceAtLeast(1)
        val step = delta / segments
        val alpha = sin(step) * (sqrt(4 + 3 * tan(step / 2) * tan(step / 2)) - 1) / 3

        var theta = theta1
        var px = x0.toDouble()
        var py = y0.toDouble()

        repeat(segments) {
            val thetaNext = theta + step
            val cosT = cos(theta); val sinT = sin(theta)
            val cosN = cos(thetaNext); val sinN = sin(thetaNext)

            // E(θ)：单位圆上一点经半径/旋转映射后的坐标；E'(θ) 是其导数
            val exN = cx + rx * cosN * cosPhi - ry * sinN * sinPhi
            val eyN = cy + rx * cosN * sinPhi + ry * sinN * cosPhi

            val dxT = -rx * sinT * cosPhi - ry * cosT * sinPhi
            val dyT = -rx * sinT * sinPhi + ry * cosT * cosPhi
            val dxN = -rx * sinN * cosPhi - ry * sinN * sinPhi
            val dyN = -rx * sinN * sinPhi + ry * cosN * cosPhi

            path.cubicTo(
                (px + alpha * dxT).toFloat(), (py + alpha * dyT).toFloat(),
                (exN - alpha * dxN).toFloat(), (eyN - alpha * dyN).toFloat(),
                exN.toFloat(), eyN.toFloat()
            )

            px = exN; py = eyN
            theta = thetaNext
        }
    }

    private fun vectorAngle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
        val dot = ux * vx + uy * vy
        val len = sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy))
        if (len < EPS) return 0.0
        var a = acos((dot / len).coerceIn(-1.0, 1.0))
        if (ux * vy - uy * vx < 0) a = -a
        return a
    }

    /** d 属性的扫描器。数字边界规定很松：`10-5` 是两个数，`1.5.5` 也是两个数。 */
    private class Scanner(private val s: String) {
        private var i = 0

        fun hasMore() = i < s.length

        fun peek(): Char = s[i]

        fun next(): Char = s[i++]

        fun skipSeparators() {
            while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++
        }

        fun number(): Float {
            skipSeparators()
            val start = i
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            while (i < s.length && s[i].isDigit()) i++
            if (i < s.length && s[i] == '.') {
                i++
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                val save = i
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i < s.length && s[i].isDigit()) {
                    while (i < s.length && s[i].isDigit()) i++
                } else {
                    i = save   // 不是合法指数，回退
                }
            }
            if (i == start) throw NumberFormatException("位置 $start 处没有数字")
            return s.substring(start, i).toFloat()
        }

        /**
         * 弧的 large-arc / sweep 标志位。规范里只占一个字符，所以 `a1 1 0 0110 10`
         * 这种写法是合法的（把 `0110` 拆成 `0` `1` `1` 再跟 `0`），不能按普通数字读。
         */
        fun flag(): Boolean {
            skipSeparators()
            val c = s[i]
            if (c != '0' && c != '1') throw NumberFormatException("标志位必须是 0 或 1，实际是 '$c'")
            i++
            return c == '1'
        }
    }
}
