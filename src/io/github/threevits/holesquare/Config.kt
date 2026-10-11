package io.github.threevits.holesquare

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个形状自己的一整套摆放参数。
 *
 * [sizePx] 是正方形区域的边长；[offsetXPx] / [offsetYPx] 是在
 * **OPPO 属性给出的孔心**基础上的额外位移。
 *
 * 三者都**未必等于真值**：实测 72px 的方块包不住屏幕上的黑圆，
 * 而且黑边露得不均匀 —— 说明真实孔径更大、孔心也不在属性标的那个点上。
 *
 * 偏移的直觉："方块往露黑的那边挪"。
 * 露在右 → X 调大；露在左 → X 调小；露在上 → Y 调小；露在下 → Y 调大。
 */
data class ShapeParams(
    val sizePx: Int,
    val offsetXPx: Int = 0,
    val offsetYPx: Int = 0
)

/**
 * 一个自定义项：名字 + SVG 源码 + 它自己那套参数。
 *
 * [id] 是生成后**永不改变**的标识 —— 改名、改内容都不动它。
 * 把"名字"和"身份"分开是必须的：拿名字当 key 的话，一改名所有引用（当前选中项、
 * 参数索引）就全断了。
 */
data class CustomShape(
    val id: String,
    val name: String,
    val svg: String,
    val params: ShapeParams
)

/**
 * 当前配置。
 *
 * [selection] 是当前选中项的 key：内置形状用 [HoleShape.id]（`square` / `star` …），
 * 自定义项用 [CustomShape.id]。
 *
 * **每个项各带一套 [ShapeParams]** —— 方形要 82px 才刚好盖住那个圆，五角星要 129px、
 * 圆角矩形又是 116px，共用一个尺寸的话每切一次都得重调。
 */
data class HoleConfig(
    val selection: String,
    /** 内置形状各自的参数 */
    val builtinParams: Map<HoleShape, ShapeParams> = emptyMap(),
    val customs: List<CustomShape> = emptyList()
) {
    val selectedCustom: CustomShape?
        get() = customs.firstOrNull { it.id == selection }

    /** 选中的是内置形状时才有值 */
    val builtin: HoleShape?
        get() = if (selectedCustom != null) null else HoleShape.fromIdOrNull(selection)

    val params: ShapeParams
        get() = selectedCustom?.params
            ?: builtinParams[builtin ?: HoleShape.DEFAULT]
            ?: defaultParamsFor(builtin ?: HoleShape.DEFAULT)

    // 转发，让读取方不用关心"参数是按项存的"这件事
    val sizePx: Int get() = params.sizePx
    val offsetXPx: Int get() = params.offsetXPx
    val offsetYPx: Int get() = params.offsetYPx

    /** 选中项的 SVG 源码。内置形状为空串。 */
    val svg: String get() = selectedCustom?.svg.orEmpty()

    /** 给界面和日志看的显示名 */
    val label: String get() = selectedCustom?.name ?: (builtin ?: HoleShape.DEFAULT).label

    /** 改当前选中项的参数。 */
    fun withParams(params: ShapeParams): HoleConfig =
        selectedCustom?.let { item ->
            copy(customs = customs.map { if (it.id == item.id) it.copy(params = params) else it })
        } ?: copy(builtinParams = builtinParams + ((builtin ?: HoleShape.DEFAULT) to params))

    /** 改当前选中自定义项的内容。对内置形状是空操作。 */
    fun withSvg(svg: String): HoleConfig =
        selectedCustom?.let { item ->
            copy(customs = customs.map { if (it.id == item.id) it.copy(svg = svg) else it })
        } ?: this

    companion object {
        /**
         * 方形/自定义的默认边长 = **实测值 82**，不是 OPPO 属性写的 72 ——
         * 属性少报了 10px，照它来会盖不住那个黑圆（见 HoleGeometry 里的说明）。
         */
        const val DEFAULT_SIZE_PX = HoleGeometry.MEASURED_HOLE_DIAMETER_PX

        /**
         * 五角星的默认边长 —— **实测调出来的**。
         *
         * 星星轮廓上离中心**最近**的地方是凹角（不是尖角），圆孔最先从那儿露出来，
         * 所以它要的尺寸比方形的 82 大一截；但顶上那个尖又伸得远，给太大就顶进
         * 状态栏的图标区了。129 是两头都合适的值。
         */
        const val STAR_DEFAULT_SIZE_PX = 129

        /**
         * 圆角矩形的默认边长 —— **实测调出来的**。
         *
         * 视野 200×200 里那个 rect 是 140 宽，缩到 116 时实际画出来
         * `140 × 116 / 200 = 81.2px`，跟 ⌀82 的孔差 0.8px，肉眼看不出。
         */
        const val ROUNDED_DEFAULT_SIZE_PX = 116

        /**
         * 渐变横条的默认边长。
         *
         * 这一项跟别的不一样：**这个数字就是横条的实际宽度**，不是"窗口边长"那种抽象量。
         * 视野是 260×82（宽高比 3.17），而窗口是正方形、SvgDrawable 又是保持长宽比
         * 居中缩放的（`scale = min(260/260, 260/82) = 1.0`），所以 260 意味着
         * **按 1:1 渲染** —— SVG 里写的那些坐标就是设备上的像素。
         */
        const val BAR_DEFAULT_SIZE_PX = 260

        /** 五角星的默认纵向偏移比别人多 1px —— 实测调出来的，别顺手改成 2。 */
        const val STAR_DEFAULT_OFFSET_Y_PX = 3

        /**
         * 自定义项的默认边长 = 孔径的 1.7 倍。
         *
         * 比方形大一圈，好容纳"实心核心只占一部分、边缘还要淡出"这类图案 ——
         * 光晕模板就是靠这个留出淡出空间的。
         */
        const val CUSTOM_DEFAULT_SIZE_PX = (DEFAULT_SIZE_PX * 1.7f).toInt()

        /** 实测的真孔心相对属性孔心 (632, 76) 的偏移：水平方向属性是准的，垂直差 +2。 */
        const val DEFAULT_OFFSET_X_PX = 0
        const val DEFAULT_OFFSET_Y_PX = 2

        /** 边长允许范围 */
        const val MIN_SIZE_PX = 8
        const val MAX_SIZE_PX = 400

        /** 偏移允许范围。真孔心偏离属性中心几十 px 量级就顶天了。 */
        const val MAX_OFFSET_PX = 200

        fun clampSize(size: Int) = size.coerceIn(MIN_SIZE_PX, MAX_SIZE_PX)
        fun clampOffset(offset: Int) = offset.coerceIn(-MAX_OFFSET_PX, MAX_OFFSET_PX)

        fun defaultParamsFor(shape: HoleShape) = when (shape) {
            HoleShape.STAR ->
                ShapeParams(STAR_DEFAULT_SIZE_PX, DEFAULT_OFFSET_X_PX, STAR_DEFAULT_OFFSET_Y_PX)

            HoleShape.ROUNDED ->
                ShapeParams(ROUNDED_DEFAULT_SIZE_PX, DEFAULT_OFFSET_X_PX, DEFAULT_OFFSET_Y_PX)

            HoleShape.BAR ->
                ShapeParams(BAR_DEFAULT_SIZE_PX, DEFAULT_OFFSET_X_PX, DEFAULT_OFFSET_Y_PX)

            // NONE 什么都不画，参数给什么都无所谓 —— 照常给一套，免得界面上空着
            else ->
                ShapeParams(DEFAULT_SIZE_PX, DEFAULT_OFFSET_X_PX, DEFAULT_OFFSET_Y_PX)
        }

        fun defaultParams() =
            ShapeParams(CUSTOM_DEFAULT_SIZE_PX, DEFAULT_OFFSET_X_PX, DEFAULT_OFFSET_Y_PX)

        fun default() = HoleConfig(HoleShape.DEFAULT.id)

        /** 新建自定义项时给的模板：中心实心、边缘淡出，起点直接可用 */
        val TEMPLATE_SVG = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
              <defs><radialGradient id="glow" cx="50%" cy="50%" r="50%">
                <stop offset="0%" stop-color="#000000" stop-opacity="1"/>
                <stop offset="86%" stop-color="#000000" stop-opacity="1"/>
                <stop offset="100%" stop-color="#000000" stop-opacity="0"/>
              </radialGradient></defs>
              <circle cx="50" cy="50" r="50" fill="url(#glow)"/>
            </svg>
        """.trimIndent()
    }
}

/**
 * 配置的存储与跨进程传递。
 *
 * ── 为什么不能直接读 SharedPreferences ──
 *
 * app 和 hook 跑在两个进程里：app 是「挖孔黑方块」自己，hook 在 SystemUI 里。
 * hook 里调 `getSharedPreferences` 读到的是 **SystemUI 自己的** prefs，不是我们的 ——
 * 静默地拿到错数据，是最阴的一类 bug。
 *
 * 而且 Android 11+ 之后 `/data/data/<pkg>` 别的应用进不去，直接读文件也不行。
 *
 * 所以走 ContentProvider：app 把值写进自己的 prefs，同时对外暴露一个只读的 provider；
 * hook 用 ContentResolver 按 authority 去查。这是 LSPosed 官方推荐的做法。
 *
 * 实时更新走 ContentObserver：app 改完调一次 `notifyChange`，hook 那边的观察者就醒了，
 * 不用重启 SystemUI。
 *
 * ── 存法 ──
 *
 * 内置形状各占一套键（`size_px_square`、`offset_x_star`…）。
 * 自定义项是一张**列表**，序列化成 JSON 存在一个键里 —— prefs 只支持标量，
 * 而列表要能任意增删改名，JSON 最省事，`org.json` 又是 framework 自带的。
 *
 * provider 对外只暴露**当前选中项**那一套 —— hook 只关心当前要画什么。
 */
object Config {

    const val AUTHORITY = "io.github.threevits.holesquare.config"

    /** app 自己用的 prefs 文件名 */
    const val PREFS_NAME = "config"

    const val KEY_SELECTION = "shape"
    const val KEY_CUSTOMS = "customs"

    /** 旧版单自定义项的键，只用来做一次迁移 */
    private const val KEY_LEGACY_SVG = "svg"

    /** provider 的 URI，hook 拿它去查、app 拿它去 notifyChange */
    val URI: Uri = Uri.parse("content://$AUTHORITY/config")

    /**
     * SVG 源码长度上限。
     *
     * 它要经 Cursor 跨进程传（CursorWindow 默认 2MB），每次改配置都会整条重传一次。
     * 正常图标撑死几 KB，给到 128KB 已经很宽松，同时把「粘了一整个网页」挡在外面。
     */
    const val MAX_SVG_CHARS = 128 * 1024

    private fun sizeKey(shape: HoleShape) = "size_px_${shape.id}"
    private fun offsetXKey(shape: HoleShape) = "offset_x_${shape.id}"
    private fun offsetYKey(shape: HoleShape) = "offset_y_${shape.id}"

    // ───────── 以下只在 hook（SystemUI 进程）里调用 ─────────

    /**
     * 跨进程读当前配置（只含当前选中项）。内置项和自定义项走同一个口子，
     * 靠 [COL_SVG] 是否为空来区分 —— 空的按内置形状的 id 去查，非空的就是自定义。
     *
     * **查不到时返回 null，不返回默认值。**
     * 这两件事必须区分开：SystemUI 启动得很早，那时候 app 进程多半还没起来，
     * `query` 会失败 —— 而失败和「用户真的选了方形」会得到同一个结果，
     * 一旦混为一谈，重启后配置就会悄悄退回默认且再也纠正不回来。
     * 调用方拿到 null 就该重试。
     */
    fun queryOrNull(context: Context): HoleConfig? = try {
        context.contentResolver.query(URI, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val selection = cursor.getString(cursor.getColumnIndexOrThrow(COL_SELECTION))
                val svg = cursor.getString(cursor.getColumnIndexOrThrow(COL_SVG)).orEmpty()
                val params = ShapeParams(
                    sizePx = HoleConfig.clampSize(cursor.getInt(cursor.getColumnIndexOrThrow(COL_SIZE))),
                    offsetXPx = HoleConfig.clampOffset(cursor.getInt(cursor.getColumnIndexOrThrow(COL_OFFSET_X))),
                    offsetYPx = HoleConfig.clampOffset(cursor.getInt(cursor.getColumnIndexOrThrow(COL_OFFSET_Y)))
                )

                if (svg.isNotEmpty()) {
                    HoleConfig(
                        selection = selection,
                        customs = listOf(CustomShape(selection, selection, svg, params))
                    )
                } else {
                    val shape = HoleShape.fromId(selection)
                    HoleConfig(selection = shape.id, builtinParams = mapOf(shape to params))
                }
            } else {
                null
            }
        }
    } catch (t: Throwable) {
        null
    }

    // ───────── 以下两个只在 app 进程里调用 ─────────
    //
    // hook 在 SystemUI 进程里绝不能碰这两个 —— 会读到 SystemUI 自己的 prefs。
    // 那边要用 ConfigProvider 查。

    fun read(context: Context): HoleConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val builtin = HoleShape.entries.associateWith { shape ->
            val fallback = HoleConfig.defaultParamsFor(shape)
            ShapeParams(
                sizePx = HoleConfig.clampSize(prefs.getInt(sizeKey(shape), fallback.sizePx)),
                offsetXPx = HoleConfig.clampOffset(prefs.getInt(offsetXKey(shape), fallback.offsetXPx)),
                offsetYPx = HoleConfig.clampOffset(prefs.getInt(offsetYKey(shape), fallback.offsetYPx))
            )
        }

        val customs = decodeCustoms(prefs.getString(KEY_CUSTOMS, null))

        var selection = prefs.getString(KEY_SELECTION, null)

        // 迁移：旧版把单个自定义项存在 `shape=custom` + `svg=…`。转成一个命名项，
        // 免得升级后用户粘的图凭空消失。
        if (selection == LEGACY_CUSTOM_VALUE) {
            val legacySvg = prefs.getString(KEY_LEGACY_SVG, null).orEmpty()
            if (legacySvg.isNotBlank()) {
                val item = CustomShape(
                    id = newId(),
                    name = "自定义 1",
                    svg = legacySvg.take(MAX_SVG_CHARS),
                    params = HoleConfig.defaultParams()
                )
                selection = item.id
                return HoleConfig(selection, builtin, customs + item)
            }
            selection = HoleShape.DEFAULT.id
        }

        if (selection == null || (HoleShape.fromIdOrNull(selection) == null &&
                customs.none { it.id == selection })
        ) {
            selection = HoleShape.DEFAULT.id
        }

        return HoleConfig(selection, builtin, customs)
    }

    fun write(context: Context, config: HoleConfig) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()

        editor.putString(KEY_SELECTION, config.selection)

        HoleShape.entries.forEach { shape ->
            val p = config.builtinParams[shape] ?: HoleConfig.defaultParamsFor(shape)
            editor.putInt(sizeKey(shape), HoleConfig.clampSize(p.sizePx))
            editor.putInt(offsetXKey(shape), HoleConfig.clampOffset(p.offsetXPx))
            editor.putInt(offsetYKey(shape), HoleConfig.clampOffset(p.offsetYPx))
        }

        editor.putString(KEY_CUSTOMS, encodeCustoms(config.customs))
        editor.remove(KEY_LEGACY_SVG)
        editor.apply()

        // 叫醒 SystemUI 那边的观察者
        context.contentResolver.notifyChange(URI, null)
    }

    /** 生成一个不会跟已有项撞车的 id。时间戳 + 序号够用，不需要 UUID 那么重。 */
    fun newId(): String = "c${System.currentTimeMillis().toString(36)}${(0..999).random()}"

    // ── 自定义项列表的序列化 ──
    //
    // JSON 而不是自己拼分隔符：SVG 里什么字符都可能有，手写转义迟早出错。

    private fun encodeCustoms(customs: List<CustomShape>): String {
        val array = JSONArray()
        customs.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("svg", item.svg.take(MAX_SVG_CHARS))
                    put("size", HoleConfig.clampSize(item.params.sizePx))
                    put("ox", HoleConfig.clampOffset(item.params.offsetXPx))
                    put("oy", HoleConfig.clampOffset(item.params.offsetYPx))
                }
            )
        }
        return array.toString()
    }

    private fun decodeCustoms(json: String?): List<CustomShape> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                CustomShape(
                    id = id,
                    name = o.optString("name").takeIf { it.isNotBlank() } ?: "未命名",
                    svg = o.optString("svg").take(MAX_SVG_CHARS),
                    params = ShapeParams(
                        sizePx = HoleConfig.clampSize(
                            o.optInt("size", HoleConfig.CUSTOM_DEFAULT_SIZE_PX)
                        ),
                        offsetXPx = HoleConfig.clampOffset(
                            o.optInt("ox", HoleConfig.DEFAULT_OFFSET_X_PX)
                        ),
                        offsetYPx = HoleConfig.clampOffset(
                            o.optInt("oy", HoleConfig.DEFAULT_OFFSET_Y_PX)
                        )
                    )
                )
            }
        } catch (_: Throwable) {
            // JSON 坏了就当成空列表 —— 总比整个 app 起不来强
            emptyList()
        }
    }

    /** 旧版把"自定义 SVG"当成一个固定形状，选中值时写的就是这个字符串 */
    private const val LEGACY_CUSTOM_VALUE = "custom"

    internal const val COL_SELECTION = "shape"
    internal const val COL_SIZE = "size_px"
    internal const val COL_OFFSET_X = "offset_x"
    internal const val COL_OFFSET_Y = "offset_y"
    internal const val COL_SVG = "svg"
}
