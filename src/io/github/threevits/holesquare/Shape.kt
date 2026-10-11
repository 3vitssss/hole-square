package io.github.threevits.holesquare

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable

/**
 * 内置形状。**枚举顺序就是界面上单选框的顺序**（界面是从 `HoleShape.entries` 生成的）。
 *
 * ── 加新内置形状的方法 ──
 *
 * 往枚举里加一项，然后在 [buildDrawable] 里给出对应的 Drawable 就行 ——
 * 界面上的单选框会自动出现。默认大小要去 `HoleConfig.defaultParamsFor` 里补一条，
 * 不补的话走 `else` 分支（孔径那么大），图案八成包不住那个物理孔。
 *
 * 自定义项（用户自己粘 SVG、自己命名、可增删的）不走这个枚举，
 * 它们在 [HoleConfig.customs] 里，见 [drawableFor]。
 *
 * ── 关于物理孔那个躲不掉的圆 ──
 *
 * 挖孔是面板上的一个物理圆洞，永远黑着，擦不掉。
 * 所以最终你看到的是「形状 ∪ 这个圆」，于是**每个图案都得把那个圆包进去**，
 * 否则圆会从图案里鼓出来（五角星的凹角、横条的淡出段都是最容易露的地方）。
 * 各形状的默认大小就是照这条算的，数字来历见 [HoleConfig] 里的常量注释。
 */
enum class HoleShape(val id: String, val label: String) {

    /**
     * 什么都不画。
     *
     * 不是「画一个小到看不见的东西」—— 是真的一个像素都不画。
     * 这样无论在什么机型上、孔算得准不准，效果都确定是「关掉」。
     * 想恢复挖孔原样又不想卸载模块时用它。
     */
    NONE("none", "无"),

    /**
     * 纯黑方块。**量真实孔径用的就是它** ——
     * 调大到黑边刚好消失，那个边长就是孔径（形状和圆同心，方形包住圆的条件就是边长 ≥ 直径）。
     */
    SQUARE("square", "方形"),

    STAR("star", "五角星"),

    ROUNDED("rounded", "圆角矩形"),

    BAR("bar", "渐变横条");

    fun buildDrawable(): Drawable = when (this) {
        NONE -> ColorDrawable(Color.TRANSPARENT)
        SQUARE -> ColorDrawable(Color.BLACK)
        STAR -> svgOrBlack(SVG_STAR)
        ROUNDED -> svgOrBlack(SVG_ROUNDED)
        BAR -> svgOrBlack(SVG_BAR)
    }

    /** 解析不出来就退回实心方块 —— 宁可画个方块，也不要屏幕上那块直接空掉。 */
    private fun svgOrBlack(svg: String): Drawable =
        SvgDrawable.parse(svg) ?: ColorDrawable(Color.BLACK)

    companion object {
        /**
         * 新装、或者存着的 id 认不出来时的兜底。
         *
         * **刻意不是 [NONE]** —— 刚装上就得能看见效果，不然用户会以为模块没生效。
         */
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

// ───────── 内置图案的源码 ─────────
//
// 都是纯黑填充。窗口是**正方形**，而 SvgDrawable 是「保持长宽比缩放到 bounds 内再居中」
// （`scale = min(bw/vw, bh/vh)`），所以宽高比不等于 1 的图案（横条是 260×82）
// 会 1:1 摆进去、上下留白，**不会被拉变形**。
//
// SVG 里的注释是**故意留着的** —— 那些数字（圆角半径、渐变 offset、内切圆半径）
// 都不是随手写的，改之前先读一遍。

/**
 * 圆角五角星，145×145 的正方形视野。
 *
 * 角是圆弧不是尖的：尖角 t=14.5、凹角 t=3.2（刻意不一样大）。
 * **凹角不能跟着一起大圆** —— 五角星的内切圆半径由凹角决定，凹角一圆，
 * 内切圆就急剧缩小，圆孔会从凹角处露出来（见 SVG 里的注释）。
 */
private val SVG_STAR = """
<svg xmlns="http://www.w3.org/2000/svg" width="145" height="145" viewBox="0 0 145.04 145.04">
  <!--
    圆角五角星。整体填充纯黑，没有渐变。

    角是**圆形弧线**，不是尖的：每个顶点按内角用「切线切角」的做法处理 ——
    切点到顶点的距离 d = t / tan(θ/2)，那段圆弧再用三次贝塞尔逼近，
    控制点离切点 (4/3)·tan(Δ/4)·t（Δ 是弧的转角）。这是圆弧的标准贝塞尔近似。

    两处圆角刻意不一样大：
      尖角  t = 14.5px（0.16×R）—— 大圆，尖角变成饱满的弧线
      凹角  t = 3.2px（0.035×R）—— 只微圆

    凹角为什么不能一起大圆：**五角星的内切圆半径由凹角决定**（那是轮廓上离中心最近的地方）。
    凹角一圆，边界就整体往中心收 —— 半径变成 r − 2.24t 左右。圆多了内切圆急剧缩小，
    就盖不住 ⌀82 的挖孔了（圆的边会从凹角处露出来）。
    当前参数下内切圆半径 43.0px > 41，够用。

    视野是正方形、中心就是星心，所以形状会正对在挖孔上。
    大小设 145px 时按 1:1 渲染（内置默认给的是 129，见 HoleConfig）。

    内半径/外半径 = 0.47（经典五角星是 0.382，很瘦；这里饱满一些更耐看）。
  -->
  <path d="M 61.28,7.30 C 65.62,-2.43 79.43,-2.43 83.77,7.30 C 97.23,37.49 98.20,38.20 99.33,38.32 C 141.67,42.79 145.94,55.93 138.03,63.06 C 113.47,85.19 113.10,86.34 113.33,87.45 C 122.17,129.10 110.99,137.22 101.76,131.90 C 73.13,115.38 71.92,115.38 70.94,115.95 C 34.05,137.22 22.88,129.10 25.09,118.68 C 31.95,86.34 31.57,85.19 30.74,84.44 C -0.90,55.93 3.37,42.79 13.97,41.67 C 46.84,38.20 47.82,37.49 48.28,36.46 Z" fill="#000000"/>
</svg>
""".trim()

/** 圆角矩形，200×200 视野里那个 rect 是 140 宽、rx=20。 */
private val SVG_ROUNDED = """
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200" width="200" height="200">
  <rect x="30" y="30" width="140" height="140" rx="20" ry="20" fill="#000000"/>
</svg>
""".trim()

/**
 * 中间实心、两侧平滑淡出的横条（直角矩形）。
 * 坐标系就是实际像素：260 宽 × 82 高 —— 所以它的「大小」设 260 时是 1:1。
 *
 * 淡出必须用 smoothstep（`3s² − 2s³`）而不是线性：线性淡出在末端斜率不为零，
 * alpha 撞到 0 会留下一条**看得见的终止线**。另外中心实心段有硬约束，见 SVG 内注释。
 */
private val SVG_BAR = """
<svg xmlns="http://www.w3.org/2000/svg" width="260" height="82" viewBox="0 0 260 82">
  <!--
    中间实心、左右淡出的横条（直角矩形）。坐标系就是实际像素：260 宽 × 82 高。

    实心段 x 97 → 163，宽 66px，居中在 x=130 上。
    ⚠️ 66px 比 ⌀82 的挖孔窄，圆的左右两端各 8px 会露在淡出区里。
       实测那块 alpha ≈ 0.94，肉眼基本看不出（要完全盖住得把实心做到 ≥82px：
       L = (260-82)/2 = 89 → offset 89/260 = 0.3423）。

    两侧各 97px 淡出，用 8 个点逼近 smoothstep 曲线 alpha(s) = 3s² − 2s³
    （s=0 在外端，s=1 在实心边缘）。两端斜率都是零，是"滑"进全透明的。
    用线性的话末端斜率不为零，alpha 撞到 0 会留下一条能看出来的终止线。

    注意 offset 永远是渐变向量的比例（0~1），跟 gradientUnits 无关，所以 offset = x / 260。
  -->
  <defs>
    <linearGradient id="bar" x1="0" y1="0" x2="260" y2="0" gradientUnits="userSpaceOnUse">
      <stop offset="0.0000" stop-color="#000000" stop-opacity="0.0000"/>
      <stop offset="0.0560" stop-color="#000000" stop-opacity="0.0607"/>
      <stop offset="0.1119" stop-color="#000000" stop-opacity="0.2160"/>
      <stop offset="0.1679" stop-color="#000000" stop-opacity="0.4253"/>
      <stop offset="0.2238" stop-color="#000000" stop-opacity="0.6480"/>
      <stop offset="0.2798" stop-color="#000000" stop-opacity="0.8438"/>
      <stop offset="0.3358" stop-color="#000000" stop-opacity="0.9720"/>
      <stop offset="0.3731" stop-color="#000000" stop-opacity="1.0000"/>
      <stop offset="0.6269" stop-color="#000000" stop-opacity="1.0000"/>
      <stop offset="0.6642" stop-color="#000000" stop-opacity="0.9720"/>
      <stop offset="0.7202" stop-color="#000000" stop-opacity="0.8438"/>
      <stop offset="0.7762" stop-color="#000000" stop-opacity="0.6480"/>
      <stop offset="0.8321" stop-color="#000000" stop-opacity="0.4253"/>
      <stop offset="0.8881" stop-color="#000000" stop-opacity="0.2160"/>
      <stop offset="0.9440" stop-color="#000000" stop-opacity="0.0607"/>
      <stop offset="1.0000" stop-color="#000000" stop-opacity="0.0000"/>
    </linearGradient>
  </defs>

  <rect x="0" y="0" width="260" height="82" fill="url(#bar)"/>
</svg>
""".trim()
