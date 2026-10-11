package io.github.threevits.holesquare

import android.content.Context
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 在挖孔位置常驻一块黑形状，大小可调。
 *
 * ── 为什么值得做 ──
 *
 * 挖孔是面板上一个**物理圆洞**（镜头），它永远是黑的，擦不掉。
 * 因为它是物理的，Android 帧缓冲里那一块其实画的是正常内容，只是送不到你眼里
 * （所以在那个位置画黑「圆」等于什么都没做）。只有画别的东西才有视觉效果。
 *
 * 最终看到的是「形状 ∪ 物理圆」，所以：
 *
 *   方形可以当尺子用 —— 调大到黑边刚好消失，那个边长就是真实孔径。
 *   （形状和圆同心，方形包住圆的条件就是边长 ≥ 圆直径。）
 *
 * ── 实现 ──
 *
 * 模块跑在 SystemUI 进程里，而 SystemUI 持有
 * SYSTEM_ALERT_WINDOW + INTERNAL_SYSTEM_WINDOW + MANAGE_APP_TOKENS，
 * 所以可以直接 addView 一个系统窗。窗口类型取 2024（TYPE_NAVIGATION_BAR_PANEL），
 * 跟系统的 ScreenDecorOverlay 同级，见下面 WINDOW_TYPE 的注释。
 *
 * 配置从 ConfigProvider 读（app 和 hook 不同进程，没法直接读 prefs），
 * 之后靠 ContentObserver 接收 app 的改动，实时换，不用重启 SystemUI。
 */
class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {

        if (lpparam.packageName != PKG_SYSTEMUI) return

        // SystemUIApplication 是整个 SystemUI 进程的 Application，
        // 它的 onCreate 是所有组件起来之前最早能拿到 Context 的时机之一。
        XposedHelpers.findAndHookMethod(
            CLS_APP, lpparam.classLoader, "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as? Context ?: return
                    // 丢到主线程队列尾部，避开 Application.onCreate 还没走完的窗口期
                    Handler(app.mainLooper).post { install(app) }
                }
            }
        )
        XposedBridge.log("[$TAG] 已挂上 $CLS_APP#onCreate")
    }

    /**
     * 整个流程都必须包在 try 里。
     *
     * 这个方法是丢到 Handler 上跑的，抛出的异常会被 Handler 吞掉 ——
     * 表现是「窗口加上了、日志没了、后面的 ContentObserver 也没注册」，
     * 现场一片安静，极难查。第一次就踩了这个坑。
     */
    private fun install(ctx: Context) {
        try {
            val holeRect = HoleGeometry.readRect()
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val view = ShapeView(ctx).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val params = layoutParamsFor(holeRect, view.config)
            wm.addView(view, params)

            // 装上的这一刻可能本来就是横屏（SystemUI 在横屏下重启时就会），
            // 所以初始状态得先问一次，不能默认「竖屏」等着后面被通知。
            view.showShape = HoleGeometry.isUpright(ctx)
            watchRotation(ctx, view)

            XposedBridge.log(
                "[$TAG] 窗口已就位 ${params.width}x${params.height} @ (${params.x}, ${params.y})，" +
                    "属性里的挖孔 ${HoleGeometry.describe(holeRect)}，" +
                    "屏幕旋转 ${HoleGeometry.rotationDegrees(ctx)}°（${describeOrientation(view)}）"
            )

            // app 改配置后调 notifyChange，这里就会被叫醒。
            // 必须用 updateViewLayout 换尺寸 —— 直接给 view.layoutParams 赋值不会动已加上的窗口。
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    try {
                        val newConfig = Config.queryOrNull(ctx) ?: return
                        view.config = newConfig
                        wm.updateViewLayout(view, layoutParamsFor(holeRect, newConfig))
                        XposedBridge.log("[$TAG] 已切到 ${describe(newConfig)}")
                    } catch (t: Throwable) {
                        XposedBridge.log("[$TAG] 更新失败：$t")
                    }
                }
            }
            ctx.contentResolver.registerContentObserver(Config.URI, false, observer)

            // 再同步一次。开机这一瞬间 app 进程多半还没起来，provider 查不到 ——
            // 所以不能只查一次就认，得重试到它应答为止。
            syncConfig(ctx, view, wm, holeRect, 0)
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] install 失败：$t")
        }
    }

    /**
     * 盯着屏幕旋转。
     *
     * 用 DisplayManager.DisplayListener 而不是等配置变化 —— 它报的就是「显示变了」
     * 这件事本身，横竖屏切换必然触发。
     *
     * 旋转刚发生时读到的 rotation 有可能是旧值（显示服务先广播、窗口系统后跟上），
     * 所以除了当场处理，还补一次延迟复查。反正 [applyOrientation] 是幂等的，
     * 重复调用只会早退。
     */
    private fun watchRotation(ctx: Context, view: ShapeView) {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: run {
            XposedBridge.log("[$TAG] 拿不到 DisplayManager，横屏不显示这一条不生效")
            return
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                applyOrientation(ctx, view, "旋转变化")
                Handler(Looper.getMainLooper()).postDelayed(
                    { applyOrientation(ctx, view, "旋转复查") },
                    ORIENTATION_RECHECK_DELAY_MS
                )
            }
        }
        dm.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
    }

    /** 按当前方向开关形状。幂等 —— 状态没变就直接返回。 */
    private fun applyOrientation(ctx: Context, view: ShapeView, why: String) {
        try {
            val upright = HoleGeometry.isUpright(ctx)
            if (view.showShape == upright) return
            view.showShape = upright
            XposedBridge.log(
                "[$TAG] $why：旋转 ${HoleGeometry.rotationDegrees(ctx)}° → ${describeOrientation(view)}"
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] 方向处理失败：$t")
        }
    }

    private fun describeOrientation(view: ShapeView): String =
        if (view.showShape) "竖屏，显示" else "非竖屏，不显示"

    private fun syncConfig(
        ctx: Context,
        view: ShapeView,
        wm: WindowManager,
        holeRect: IntArray,
        attempt: Int
    ) {
        val config = Config.queryOrNull(ctx)
        if (config != null) {
            view.config = config
            wm.updateViewLayout(view, layoutParamsFor(holeRect, config))
            XposedBridge.log("[$TAG] 配置同步为 ${describe(config)}（第 ${attempt + 1} 次尝试）")
            return
        }
        if (attempt >= MAX_SYNC_ATTEMPTS) {
            XposedBridge.log("[$TAG] provider 一直没应答，保持默认配置")
            return
        }
        Handler(Looper.getMainLooper()).postDelayed(
            { syncConfig(ctx, view, wm, holeRect, attempt + 1) },
            SYNC_RETRY_DELAY_MS
        )
    }

    /**
     * 算出窗口的位置和尺寸。
     *
     * 边长取 [HoleConfig.sizePx]，中心是**属性给出的孔心 + 手动偏移**。
     * 偏移是必要的：实测黑边露得不均匀，说明真孔心不在属性标的那个点上，
     * 得让人手动找齐。
     */
    private fun layoutParamsFor(
        holeRect: IntArray,
        config: HoleConfig
    ): WindowManager.LayoutParams {
        val cx = (holeRect[0] + holeRect[2]) / 2f + config.offsetXPx
        val cy = (holeRect[1] + holeRect[3]) / 2f + config.offsetYPx
        val half = config.sizePx / 2f

        return WindowManager.LayoutParams().apply {
            width = config.sizePx
            height = config.sizePx
            type = WINDOW_TYPE
            // 必须是 TRANSLUCENT，不能是 OPAQUE。
            // 五角星、横条这类图案都盖不满窗口（星角之间、横条上下都是空的），
            // 没盖到的地方得透过去看见后面的内容；
            // 而且 OPAQUE 窗口下渲染器不会每帧清缓冲，换形状时旧图形会赖着不走。
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.LEFT
            x = (cx - half).toInt()
            y = (cy - half).toInt()
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            setTitle(WINDOW_TITLE)
        }
    }

    private fun describe(config: HoleConfig): String {
        val offset = if (config.offsetXPx == 0 && config.offsetYPx == 0) {
            "无偏移"
        } else {
            "偏移 (${config.offsetXPx}, ${config.offsetYPx})"
        }
        return "「${config.label}」${config.sizePx}px $offset"
    }

    private companion object {
        const val TAG = Tag.NAME

        const val PKG_SYSTEMUI = "com.android.systemui"
        const val CLS_APP = "com.android.systemui.SystemUIApplication"

        /**
         * TYPE_NAVIGATION_BAR_PANEL (2024)。
         *
         * 刻意不用更"正常"的 TYPE_APPLICATION_OVERLAY (2038)：实测在 WindowManager 的
         * 层序表里 2038 落在 111000，而控制中心（NotificationShade）在 171000，
         * 会把它整个盖住；锁屏更是在它下面。
         *
         * 2024 是 ScreenDecorOverlay 用的类型 —— 就是系统画屏幕圆角和挖孔装饰的那个窗口，
         * 层序 571000，高于导航栏、控制中心。它画的圆角在锁屏上照样可见，
         * 说明这一层也在键盘锁之上。跟它同类型，覆盖范围就一样。
         */
        const val WINDOW_TYPE = 2024

        const val WINDOW_TITLE = "HoleSquare"

        /** 开机后向 ConfigProvider 同步配置的最大重试次数 */
        const val MAX_SYNC_ATTEMPTS = 12
        const val SYNC_RETRY_DELAY_MS = 1500L

        /** 旋转广播之后多久再确认一次方向（显示服务可能比窗口系统先到） */
        const val ORIENTATION_RECHECK_DELAY_MS = 400L

    }
}
