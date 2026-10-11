package io.github.threevits.holesquare

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface

/**
 * 挖孔的几何信息，来源是 OPPO 自己的出厂属性。
 *
 * `ro.oplus.display.screenhole.positon = 596,40:668,112`，格式 `left,top:right,bottom`。
 * 语义由 SystemUI 自己的 OplusStatusBarUtils 证实：
 *
 *     getScreenHoleRect()   = new Rect(param[0], param[1], param[2], param[3]);
 *     getScreenHoleWidth()  = param[2] - param[0];   // 668 - 596 = 72
 *     getScreenHoleHeight() = param[3] - param[1];   // 112 -  40 = 72
 *
 * app 和 hook 两边都要读，所以放这里共用 —— 读的是同一份属性，不可能对不上。
 *
 * ── 为什么这里一律用裸反射，不碰 XposedHelpers ──
 *
 * 这个类会被 **app 进程**调用（预览要用）。而 app 进程没有被 LSPosed 注入，
 * Xposed API 是 compileOnly、根本没打进 APK，一引用就是 NoClassDefFoundError。
 *
 * 裸反射在两边都成立：
 *   SystemUI 进程（被 hook）→ LSPosed 放开了隐藏 API 豁免，读得到
 *   app 进程         → 隐藏 API 被拦，安静失败，退回兜底值（就是本机的真值）
 */
object HoleGeometry {

    private const val PROP = "ro.oplus.display.screenhole.positon"

    /** 一加 Ace 3 (PJE110) 的出厂值，读不到时用这个。 */
    private val FALLBACK = intArrayOf(596, 40, 668, 112)

    /** 兜底屏幕宽度（像素）。 */
    private const val FALLBACK_SCREEN_WIDTH = 1264

    /** @return [left, top, right, bottom]，挖孔的包围盒 */
    fun readRect(): IntArray {
        try {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java, String::class.java)
            val raw = get.invoke(null, PROP, "") as? String
            if (!raw.isNullOrBlank()) {
                val n = raw.split(',', ':').mapNotNull { it.trim().toIntOrNull() }
                if (n.size >= 4 && n[2] > n[0] && n[3] > n[1]) {
                    return intArrayOf(n[0], n[1], n[2], n[3])
                }
            }
        } catch (_: Throwable) {
            // 隐藏 API 被拦 / 属性不存在 —— 都退回兜底值
        }
        return FALLBACK.copyOf()
    }

    /** 屏幕宽度（像素）。预览只用它算比例，差几个像素无所谓。 */
    fun screenWidthPx(context: Context): Int {
        val w = context.resources.displayMetrics.widthPixels
        return if (w > 0) w else FALLBACK_SCREEN_WIDTH
    }

    /** 属性定义的孔：挖孔包围盒的内切圆。**只作参考，它不是真值。** */
    fun holeCircle(rect: IntArray): FloatArray {
        val cx = (rect[0] + rect[2]) / 2f
        val cy = (rect[1] + rect[3]) / 2f
        val r = minOf(rect[2] - rect[0], rect[3] - rect[1]) / 2f
        return floatArrayOf(cx, cy, r)
    }

    // ───────── 实测的真实孔 ─────────
    //
    // 属性（以及系统的 DisplayCutout）都**小于**面板上真正的那个圆洞。
    // 下面这组是 2026-10-10 拿这个模块当尺子量出来的：画一个可调大小的黑方块，
    // 调大到真机屏幕上的黑边刚好消失 —— 那个边长就是孔径（方形与孔同心）。
    //
    //   孔径 82 px（属性写 72，少报 10）
    //   孔心 (632, 78)（属性写 (632, 76)，差 +2）
    //
    // 换机或大版本升级后要重量：在 app 里调大小/偏移到黑边刚好消失，把这几个数改掉即可。

    const val MEASURED_HOLE_DIAMETER_PX = 82
    const val MEASURED_CENTER_X = 632f
    const val MEASURED_CENTER_Y = 78f

    /** 真实孔：圆心和半径都是实测的。预览用这个才说实话。 */
    fun measuredHoleCircle(): FloatArray =
        floatArrayOf(MEASURED_CENTER_X, MEASURED_CENTER_Y, MEASURED_HOLE_DIAMETER_PX / 2f)

    /** 给日志用 */
    fun describe(rect: IntArray): String =
        "${rect[2] - rect[0]}x${rect[3] - rect[1]} @ (${rect[0]}, ${rect[1]})"

    // ───────── 屏幕旋转 ─────────

    /**
     * 当前显示旋转：Surface.ROTATION_0 / 90 / 180 / 270。
     *
     * 读不到就返回 ROTATION_0 —— 也就是「竖屏正立」，这正是本模块从头到尾的假设，
     * 退回到它等于退回改造前的行为，不会更糟。
     */
    fun rotation(context: Context): Int = try {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        dm?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    } catch (_: Throwable) {
        Surface.ROTATION_0
    }

    /**
     * 屏幕是不是竖屏正立 —— **只有这时才画形状**。
     *
     * ── 为什么横屏就藏起来，而不是把形状挪到孔那儿 ──
     *
     * 挖孔是面板上的物理位置，不跟着内容转。竖屏时它在顶部正中，横屏时它跑到屏幕
     * 左／右侧的垂直正中 —— 而窗口坐标系是跟着内容转的，所以本模块用的那套
     * 「竖屏坐标」在横屏下全部失效（窗口会飘到屏幕另一个地方去，
     * 表现就是一块黑方块出现在不该出现的位置）。
     *
     * 挪过去需要把自然坐标按旋转映射一遍（AOSP CoordinateTransforms 那套
     * ROTATION_90 → (y, W₀ − x) 之类），而用户的选择是横屏干脆不盖 —— 于是这里
     * 只需要判断方向。**180° 也算不显示**：它是竖屏但是倒的，孔的物理位置同样
     * 跑到屏幕底部去了，照画一样是块飘着的黑方块。
     */
    fun isUpright(context: Context): Boolean = rotation(context) == Surface.ROTATION_0

    /**
     * 旋转的**角度**（0 / 90 / 180 / 270），给日志和显示用。
     *
     * 别把 [rotation] 的返回值直接当角度打 —— `Surface` 那套常量是 0/1/2/3，
     * 90° 打出来是「1°」，日志会看得人一头雾水。
     */
    fun rotationDegrees(context: Context): Int = when (rotation(context)) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
}

internal object Tag {
    const val NAME = "挖孔黑方块"
}
