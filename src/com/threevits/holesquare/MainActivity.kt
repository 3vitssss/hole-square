package com.threevits.holesquare

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/**
 * app 主界面。纯 framework View 搭的，没有 AndroidX —— 构建是手搓的，
 * 没挂 AndroidX 依赖，用 R 资源还得额外生成 R.java，索性全用代码搭。
 *
 * 形状列表是**动态**的（内置两个 + 任意多个自定义项，可增删改名），
 * 所以形状那一段是按当前 [HoleConfig.customs] 现场搭出来的，改动后要 [rebuildShapeList]。
 *
 * 布局上刻意把调节控件放在屏幕下半部：调的时候得盯着最顶上那块看效果，
 * 手放下面才不会挡住。
 */
class MainActivity : Activity() {

    private enum class Field { SIZE, OFFSET_X, OFFSET_Y }

    private lateinit var preview: PreviewView
    private lateinit var shapeGroup: RadioGroup
    private lateinit var svgField: EditText
    private lateinit var svgApplyButton: Button
    private lateinit var svgStatus: TextView
    private lateinit var svgTitle: TextView

    private val fields = HashMap<Field, EditText>()

    /** 单选按钮的 id ↔ 选中项 key。key 只有遍历时才知道，所以得双向映射 */
    private val radioIdToKey = HashMap<Int, String>()

    private var config: HoleConfig = HoleConfig.default()

    /** 防止「改配置 → 回写控件 → 又触发监听」来回打转 */
    private var syncing = false

    /** 上一次往文本框里灌过的选中项，用来判断"是不是换了项"而不是"用户在自己打字" */
    private var svgLoadedFor: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        config = Config.read(this)

        val pad = dp(18)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val propRect = HoleGeometry.readRect()
        val propCx = (propRect[0] + propRect[2]) / 2
        val propCy = (propRect[1] + propRect[3]) / 2
        val propSize = propRect[2] - propRect[0]

        column.addView(heading("挖孔黑方块"))

        preview = PreviewView(this)
        preview.setConfig(config)
        column.addView(preview, LinearLayout.LayoutParams(MATCH_PARENT, dp(86)).apply {
            topMargin = dp(12)
            bottomMargin = dp(8)
        })

        column.addView(
            caption(
                "屏幕最顶上那块就是效果，调的时候看那里。\n" +
                    "预览里的圆是 OPPO 属性值（${propSize}px @ ($propCx, $propCy)），它未必是真实孔径和孔心。"
            )
        )

        column.addView(gap(18))
        column.addView(sectionLabel("形状"))
        shapeGroup = RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
            // 这个监听器是"点形状真的生效"的唯一入口 —— 没有它，check() 只把按钮点亮，
            // 没人接事件，选中项既不保存也不上屏。重写界面时漏过一次，记在这儿。
            // 先判 syncing：代码里程序化改选中项（重建列表、删掉当前项）时不该回调回来。
            setOnCheckedChangeListener { _, checkedId ->
                if (syncing) return@setOnCheckedChangeListener
                val key = radioIdToKey[checkedId] ?: return@setOnCheckedChangeListener
                apply(config.copy(selection = key))
            }
        }
        column.addView(shapeGroup)

        column.addView(
            Button(this).apply {
                text = "＋ 新建自定义"
                textSize = 15f
                setOnClickListener { addCustom() }
            },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(6) }
        )
        column.addView(caption("每个项各存一套大小和偏移，切换时自动带出来，不用重调。"))

        column.addView(gap(18))
        column.addView(sectionLabel("大小（px）"))
        column.addView(stepperRow(Field.SIZE, signed = false))

        column.addView(gap(18))
        column.addView(sectionLabel("位置微调（px）"))
        column.addView(stepperRow(Field.OFFSET_X, signed = true, label = "X"))
        column.addView(stepperRow(Field.OFFSET_Y, signed = true, label = "Y").apply {
            (this as LinearLayout).setPadding(0, dp(8), 0, 0)
        })

        column.addView(gap(14))
        column.addView(
            caption(
                "① 先调 X / Y 让黑边四周露得均匀 ——\n" +
                    "   把形状往露黑的那边挪：露右 X+，露左 X−，露上 Y−，露下 Y+。\n" +
                    "② 再调大小直到黑边刚好消失。那个值就是真实孔径，\n" +
                    "   X / Y 就是真孔心相对属性中心 ($propCx, $propCy) 的偏移。"
            )
        )

        column.addView(gap(24))
        column.addView(buildSvgSection())

        rebuildShapeList()

        setContentView(ScrollView(this).apply { addView(column) })
    }

    // ───────── 形状列表（动态） ─────────

    private fun rebuildShapeList() {
        shapeGroup.removeAllViews()
        radioIdToKey.clear()

        HoleShape.entries.forEach { shape ->
            addShapeRow(key = shape.id, label = shape.label, custom = null)
        }
        config.customs.forEach { item ->
            addShapeRow(key = item.id, label = item.name, custom = item)
        }

        // 重建后必须再 check 一次把 RadioGroup 内部记的 mCheckedId 对齐。
        //
        // 光靠上面设 isChecked = true 是不够的：那只是让按钮自己亮起来，
        // RadioGroup 的 mCheckedId 还是 -1。等用户点另一个时，它去"取消旧的"会找不到目标，
        // 结果是两个同时亮着。
        syncing = true
        try {
            radioIdToKey.entries.firstOrNull { it.value == config.selection }?.let {
                shapeGroup.check(it.key)
            }
        } finally {
            syncing = false
        }
    }

    private fun addShapeRow(key: String, label: String, custom: CustomShape?) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val id = View.generateViewId()
        radioIdToKey[id] = key

        row.addView(
            RadioButton(this).apply {
                this.id = id
                text = label
                textSize = 16f
                isChecked = key == config.selection
                setPadding(0, dp(6), 0, dp(6))

                // 必须自己挂点击：RadioGroup 只在**直接子 View** 是 RadioButton 时才给它
                // 挂内部监听器（PassThroughHierarchyChangeListener），而我们是嵌在
                // LinearLayout 里的（为了并排放改名/删除按钮），它管不着。
                // 不补这一下的话，点单选框只会让按钮自己亮起来，RadioGroup 完全不知情 ——
                // OnCheckedChangedListener 不触发，旧的选中项也不会取消。
                setOnClickListener { shapeGroup.check(this.id) }
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )

        // 内置形状没有改名/删除 —— 它们不该被改
        if (custom != null) {
            row.addView(smallButton("改名") { renameCustom(custom) })
            row.addView(smallButton("删除") { deleteCustom(custom) })
        }

        shapeGroup.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun smallButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 13f
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { onClick() }
    }

    private fun addCustom() {
        val index = config.customs.size + 1
        val item = CustomShape(
            id = Config.newId(),
            name = "自定义 $index",
            svg = HoleConfig.TEMPLATE_SVG,
            params = HoleConfig.defaultParams()
        )
        apply(config.copy(selection = item.id, customs = config.customs + item))
        rebuildShapeList()
    }

    private fun renameCustom(item: CustomShape) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(item.name)
            setSelection(text.length)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                // 名字是显示用的，id 不动 —— 空名字就保留原名，别让列表出现一行空白
                val name = input.text.toString().trim().ifEmpty { item.name }
                apply(
                    config.copy(
                        customs = config.customs.map {
                            if (it.id == item.id) it.copy(name = name) else it
                        }
                    )
                )
                rebuildShapeList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteCustom(item: CustomShape) {
        AlertDialog.Builder(this)
            .setTitle("删除「${item.name}」？")
            .setMessage("它的 SVG 和那套大小 / 偏移会一起删掉，不能撤销。")
            .setPositiveButton("删除") { _, _ ->
                val remaining = config.customs.filterNot { it.id == item.id }
                // 删掉的正好是当前选中项，就退回方形
                val next = if (config.selection == item.id) HoleShape.DEFAULT.id else config.selection
                apply(config.copy(selection = next, customs = remaining))
                rebuildShapeList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ───────── 自定义 SVG ─────────

    private fun buildSvgSection(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        svgTitle = sectionLabel("自定义 SVG")
        box.addView(svgTitle)

        svgField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textSize = 11f
            gravity = Gravity.TOP or Gravity.START
            hint = "<svg viewBox=\"0 0 24 24\">…</svg>"
        }
        box.addView(svgField, LinearLayout.LayoutParams(MATCH_PARENT, dp(130)).apply {
            topMargin = dp(6)
            bottomMargin = dp(8)
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        svgApplyButton = Button(this).apply {
            text = "应用"
            textSize = 16f
            setOnClickListener { applySvg() }
        }
        row.addView(svgApplyButton, LinearLayout.LayoutParams(dp(96), dp(52)))
        svgStatus = TextView(this).apply {
            textSize = 12f
            alpha = 0.7f
            setPadding(dp(12), 0, 0, 0)
        }
        row.addView(svgStatus, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        box.addView(row)

        box.addView(
            caption(
                "粘整段 <svg>…</svg>（含外层标签），只作用于当前选中的自定义项。\n" +
                    "支持 path / rect / circle / ellipse / line / polygon、fill / stroke / " +
                    "stroke-width / opacity，以及渐变（linearGradient / radialGradient）和内联 style=\"…\"。\n" +
                    "不支持 <use>、CSS class、滤镜、动画 —— 遇到会跳过那一项。\n" +
                    "图案按原比例缩放进方块，四周留白是透明的。"
            ).apply { setPadding(0, dp(8), 0, 0) }
        )

        return box
    }

    /** 把当前选中自定义项的内容灌进文本框。只在换项时调，免得打断正在打字的人。 */
    private fun syncSvgEditor() {
        val custom = config.selectedCustom

        val editable = custom != null
        svgField.isEnabled = editable
        svgApplyButton.isEnabled = editable
        svgTitle.text = if (editable) "自定义 SVG ——「${custom!!.name}」" else "自定义 SVG"

        if (!editable) {
            svgStatus.text = "选中一个自定义项才能编辑它"
            svgField.setText("")
            svgLoadedFor = null
            return
        }

        if (svgLoadedFor != custom!!.id) {
            svgField.setText(custom.svg)
            svgLoadedFor = custom.id
            svgStatus.text = ""
        }
    }

    /** 解析一遍再落盘：解析不出来的话提示出来，并且不让它上屏。 */
    private fun applySvg() {
        val custom = config.selectedCustom ?: return
        val source = svgField.text?.toString().orEmpty().trim()

        if (source.isEmpty()) {
            svgStatus.text = "还没粘内容"
            return
        }
        if (source.length > Config.MAX_SVG_CHARS) {
            svgStatus.text = "太长了（${source.length} 字符，上限 ${Config.MAX_SVG_CHARS}）"
            return
        }
        if (!source.contains("<svg", ignoreCase = true)) {
            svgStatus.text = "没找到 <svg> 标签，是不是只粘了里面一段？"
            return
        }
        if (SvgDrawable.parse(source) == null) {
            svgStatus.text = "解析不出可画的图形 —— 可能用了不支持的写法，换个图标试试"
            return
        }

        svgStatus.text = "已应用 ✓"
        apply(config.withSvg(source))
    }

    // ───────── 数值行： [−] [输入框] [+] ─────────

    private fun stepperRow(field: Field, signed: Boolean, label: String? = null): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 没有这个标签的话，两组偏移长得一模一样，根本分不出哪行是 X 哪行是 Y
        if (label != null) {
            row.addView(
                TextView(this).apply {
                    text = label
                    textSize = 17f
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(dp(26), WRAP_CONTENT)
            )
        }

        val fieldView = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
            textSize = 20f
            gravity = Gravity.CENTER
            setText(valueOf(field).toString())
            // 监听器必须在 setText 之后再挂 —— 否则构造时那次 setText 会触发回写，
            // 而此时 fields 还没填完，同步会拿到半截状态。
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (syncing) return
                    val text = s?.toString().orEmpty()
                    // 允许输到一半的 "-" 或空，别在那儿报错
                    if (text.isEmpty() || text == "-") return
                    val value = text.toIntOrNull() ?: return
                    pick(field, value)
                }
            })
        }
        fields[field] = fieldView

        row.addView(
            Button(this).apply {
                text = "−"
                textSize = 24f
                setOnClickListener { pick(field, valueOf(field) - 1) }
            },
            LinearLayout.LayoutParams(dp(68), dp(56))
        )

        row.addView(fieldView, LinearLayout.LayoutParams(0, dp(56), 1f).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
        })

        row.addView(
            Button(this).apply {
                text = "+"
                textSize = 24f
                setOnClickListener { pick(field, valueOf(field) + 1) }
            },
            LinearLayout.LayoutParams(dp(68), dp(56))
        )

        return row
    }

    private fun valueOf(field: Field): Int = when (field) {
        Field.SIZE -> config.sizePx
        Field.OFFSET_X -> config.offsetXPx
        Field.OFFSET_Y -> config.offsetYPx
    }

    /**
     * 输入框直接填的值可能超范围，这里只 clamp 到合法区间，**不**把 clamp 后的值写回输入框 ——
     * 用户正在打字时回写会打断输入（比如输到一半的 "1" 会被改成 "8"）。
     * 回写只发生在按 +/− 或切换项时。
     */
    private fun pick(field: Field, raw: Int) {
        // 改的是"当前选中项自己那套"参数，别的项不受影响
        val p = config.params
        val next = when (field) {
            Field.SIZE -> p.copy(sizePx = HoleConfig.clampSize(raw))
            Field.OFFSET_X -> p.copy(offsetXPx = HoleConfig.clampOffset(raw))
            Field.OFFSET_Y -> p.copy(offsetYPx = HoleConfig.clampOffset(raw))
        }
        apply(config.withParams(next))
    }

    private fun apply(newConfig: HoleConfig) {
        config = newConfig
        Config.write(this, newConfig)
        preview.setConfig(newConfig)

        syncing = true
        try {
            // 单选组也得同步 —— 删掉当前项、或从代码里切选中项时，
            // 不对齐的话单选框和屏幕上画的东西会对不上
            radioIdToKey.entries.firstOrNull { it.value == newConfig.selection }?.let {
                if (shapeGroup.checkedRadioButtonId != it.key) shapeGroup.check(it.key)
            }
            fields.forEach { (field, view) ->
                val want = valueOf(field).toString()
                if (view.text?.toString() != want) {
                    view.setText(want)
                    view.setSelection(view.text.length)
                }
            }
        } finally {
            syncing = false
        }

        syncSvgEditor()
    }

    // ───────── 搭界面用的小工具 ─────────

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 21f
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        alpha = 0.6f
    }

    private fun caption(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        alpha = 0.6f
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun gap(heightDp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(heightDp))
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
