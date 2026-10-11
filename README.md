# 挖孔黑方块

一个 APK，两面身份：

- **可打开的 app** —— 有图标、有界面，能预览效果、切换形状
- **LSPosed 模块** —— 在挖孔位置画上所选形状

装上即用，改形状**立刻生效**，不用重启 SystemUI。

## 前提

- **ColorOS 系设备**（ColorOS / OxygenOS / RealmeUI）+ LSPosed
  - 需要设备**有挖孔**，并且能读到 `ro.oplus.display.screenhole.positon`（OPPO 系出厂都带这个属性）
  - 实测通过：一加 Ace 3（PJE110）/ ColorOS 16，SystemUI `16.00.12`
- **默认值是按 Ace 3 实测填的**（孔径 82px、孔心 (632,78)）。换机型不用改代码 ——
  开机时模块会读那个属性拿到位置，你只要在 app 里按「调到黑边刚好消失」的流程重新量一遍大小
- **OShin 那类模块无关**：本模块不碰流体云，跟它们不冲突

## 它在干什么

挖孔是面板上一个直径 72px 的**物理圆洞**（前置镜头），它永远是黑的，擦不掉。
而且它**不在帧缓冲里** —— Android 截图走 SurfaceFlinger 合成图层，物理孔是面板属性，
不出现在画面里（实测：JD 纯白页面上沿孔中心竖扫，一路 `(255,255,255)` 无黑点）。

于是在那个位置画什么，结果就是「画的东西 ∪ 物理圆」：

| 画什么 | 结果 |
|---|---|
| 黑**圆** | 视觉上什么都没变（本来就看不见，是个黑圆） |
| 黑**方** | 孔从圆变方 —— 四个角上原本能看见的真实像素被涂黑了 |
| **三角** | 三角 + 圆的左右两侧从斜边鼓出来（因为三角内接在 72×72 里包不住那个圆） |

**顶点在上的等腰三角形要包住同样大小的圆，边长得是圆直径的 2 倍**
（要 `S/2 ≥ r`，即 `S ≥ 直径`）。所以在 app 里把三角形调成方形所需大小的两倍左右才干净。

## 怎么量出真实孔径和孔心

**OPPO 属性给的那组数不是真值。** 实测 72px 的方块包不住屏幕上的黑圆，
而且黑边露得不均匀 —— 真实孔径更大、孔心也不在属性标的 `(632, 76)` 上。

app 里因此给了三个可调量：**大小**、**X 偏移**、**Y 偏移**（都按 px，1px 步进）。
量法：

1. 先调 X / Y 让黑边四周露得**均匀**。
   规则是「把方块往露黑的那边挪」：露右 X+，露左 X−，露上 Y−，露下 Y+。
2. 再调大小直到黑边**刚好消失**。

方形和孔同心，所以「方形刚好包住圆」时边长就等于圆的直径。
最终这组值（边长、X、Y）就是真孔径和真孔心相对属性中心的偏移。

### 实测结果（一加 Ace 3 / PJE110，2026-10-10）

| 量 | 实测 | OPPO 属性 | 差 |
|---|---|---|---|
| 孔径 | **≈82 px** | 72 px | **属性少报 10 px** |
| 孔心 x | 632 | 632 | 0 |
| 孔心 y | **78** | 76 | +2 |

也就是 `大小=82, X=0, Y=2`。
X 为 0 说明水平方向属性是准的；直径差 10px 是「包不住」的主因。
**系统的 `DisplayCutout`（76 宽）同样小于真实孔径。**

精度约 ±2px —— 黑边消失的临界点靠肉眼判断，容易略微调过头。

## 每个形状各存一套参数

大小和 X/Y 偏移是**按形状分开存**的 —— 方形要 82px 才刚好盖住，
三角形得 164px 才包得住那个圆，共用一个尺寸的话每切一次形状都得重调。

| 形状 | 默认大小 | 为什么 |
|---|---|---|
| 方形 | 82px | 实测孔径，刚好盖住 |
| 三角形 | 164px | 孔径的 2 倍。等腰三角形包住同尺寸的圆需 `边长 ≥ 直径`，给 2 倍才干净 |
| 自定义 SVG | 82px | 图标按比例缩放进方块，用方形的值当起点 |

在任一形状下调，只影响那一个；切走再切回来还是你调好的值。
prefs 里每形状一套键（`size_px_square`、`offset_x_triangle`…），
provider 只暴露**当前形状**那一套 —— hook 只关心当前要画什么。

## 自定义 SVG（可以有任意多个）

形状列表里点「＋ 新建自定义」加一项，每项都能**改名**、**删除**，各带**自己的 SVG 和自己的大小/偏移**。
选中某一项时，底部的文本框编辑的就是它。

- **改名**只改显示名。身份靠内部生成的 id —— 拿名字当 key 的话，一改名所有引用就全断了。
- **删除**带确认框，会连同那套装扮一起删掉。删掉的正好是当前选中项就自动退回「方形」。
- 自定义项列表序列化成 JSON 存在一个键里（prefs 只支持标量，而列表要能任意增删改名）。
  SVG 里什么字符都可能有，手写分隔符转义迟早出错，JSON 最省事且 `org.json` 是 framework 自带的。
- 从旧版升级时，原来那个单一自定义项会**自动迁移**成一个命名项，粘过的图不会丢。

新建时给的模板是一段「中心实心、边缘淡出」的光晕 —— 因为物理孔的约束，这种形状最实用（见下）。

### 解析支持范围

**元素**：`path` `rect` `circle` `ellipse` `line` `polyline` `polygon`
**属性**：`fill` `stroke` `stroke-width` `fill-opacity` `stroke-opacity` `opacity`
`stroke-linecap` `stroke-linejoin` `viewBox`，以及内联 `style="fill:#f00;…"`
（优先级高于同名属性 —— Figma / Inkscape 导出的 SVG 大多把属性塞在这里）
**颜色**：`none` `#RGB` `#RRGGBB` `#RRGGBBAA` `rgb()` `rgba()` `transparent` `currentColor` 和一小撮颜色名
**变换**：`<g transform>` 的 translate / scale / rotate / matrix
**渐变**：`linearGradient` / `radialGradient` + `<stop>`，支持 `gradientUnits`、
`gradientTransform`、`spreadMethod`、`xlink:href` 继承

**不支持**：`<use>` `<defs>` 里的 `<clipPath>` 滤镜、CSS `<style>` 块和 class 选择器、
`<text>`、动画。遇到会跳过那一项，不会整张挂掉。

### 渐变：怎么调「多久淡到透明」

SVG 里就是一条 alpha 渐变 —— Android 的 `LinearGradient` / `RadialGradient` 本来就吃带 alpha 的 ARGB，
所以「淡到透明」不需要特殊处理。淡出宽度由**最后一个实心色标的 offset** 决定：

```
淡出宽度(px) = (1 − offset) × 大小 ÷ 2
```

大小 140px 时：offset `60%` → 28px，`80%` → 14px，`86%` → 9.8px，`96%` → 2.8px。
注意大小和淡出是**联动**的，把大小调大淡出也会按比例变长。

### ⚠️ 渐变必须「中心实心」才好看

> **物理挖孔永远纯黑、边界锐利。任何半透明的地方，那个黑圆的硬边都会透出来。**

所以「整块从黑淡到透明」会在渐变中间**切出一个清晰的黑色圆盘**，看着像画坏了。
正确做法是**只在圆的外围做淡出** —— 中心保持不透明盖住孔，边缘柔和消失，像一团晕开的光。

下限：实心部分必须还能盖住 ⌀82 的孔。140px 时实心半径要到 41px，即 `offset ≥ 41/70 ≈ 59%`。

图案**按原比例缩放进方块、居中**，四周留白是透明的 —— 那个黑圆会从留白处露出来，
所以要么挑个能盖住圆的图形，要么把大小调大。预览里能直接看到。

找图案的地方：[Iconify](https://icon-sets.iconify.design/)（聚合 150+ 套开源图标，
搜索最好用）、[Material Symbols](https://fonts.google.com/icons)、
[Lucide](https://lucide.dev/) / [Tabler](https://tabler.io/icons) /
[Phosphor](https://phosphoricons.com/)（都 MIT）、
[SVG Repo](https://www.svgrepo.com/)（能按许可证筛）。
**注意许可**：MIT / Apache-2.0 / CC0 随便用；CC-BY 要署名；带 NC / ND 的别用在会分享出去的东西上。

### 描边型图标别慌

Lucide / Tabler / Phosphor 的图标大多是**描边型**：

```xml
<path d="M12 2 L2 22 L22 22 Z" fill="none" stroke="currentColor" stroke-width="2"/>
```

解析器把 `fill="none"` 映射成「不填充」、把 `stroke` / `stroke-width` /
线帽线接一起读出来，所以描边型图标能正确渲染，不会变成一坨实心黑块。
（`currentColor` 没有 CSS 上下文，按黑色处理。）

### 为什么是自己写的解析器

两条近路都堵死了，记在这儿免得以后又想抄近道：

1. **`Drawable.createFromXml(res, Xml.newPullParser())`** —— 看起来能直接把一段 XML 字符串
   变成 VectorDrawable。但 `Xml.asAttributeSet()` 要求 parser 实现 `AttributeSet`，
   而设备上的 `KXmlParser`（`/apex/com.android.art/javalib/core-libart.jar`）实测是：
   ```smali
   .class public Lcom/android/org/kxml2/io/KXmlParser;
   .implements Lorg/xmlpull/v1/XmlPullParser;
   .implements Ljava/io/Closeable;
   .implements Ljava/lang/AutoCloseable;
   ```
   会抛异常。（AOSP 那句报错文案 "not a parser created by Xml.newPullParser()" 本身是错的。）
2. **自己实现 `AttributeSet` 再走 `createFromXmlInner`** —— `Resources.obtainAttributes()`
   依赖 `getAttributeNameResource()` 返回 `com.android.internal.R.styleable` 的数值 ID，
   那些 ID 是隐藏的，是个泥潭。

所以自己写：`SvgPath.kt`（`d` 属性 → Path，含 M/L/H/V/C/S/Q/T/A/Z 和隐式重复命令）
+ `SvgDrawable.kt`（XML 遍历 + 样式继承 + 用 Canvas 直接画）。
全公开 API，app 进程和 SystemUI 进程行为完全一致。

## 结构

```
src/com/threevits/holesquare/
├── Shape.kt           形状枚举 + 取 Drawable（app 和 hook 共用同一份，预览不可能和实际不一致）
├── SvgPath.kt         d 属性 → Path（M/L/H/V/C/S/Q/T/A/Z、隐式重复、圆弧转贝塞尔）
├── SvgDrawable.kt     SVG 文档 → 可画的 Drawable（样式继承 / 内联 style / transform）
├── HoleGeometry.kt    从 OPPO 属性读挖孔位置（app 和 hook 共用）
├── Config.kt          配置存储 / 跨进程读取 / 按形状分存参数
├── ConfigProvider.kt  ← 暴露给 SystemUI 的只读 provider
├── PreviewView.kt     app 里的预览
├── MainActivity.kt    app 主界面（纯 framework View）
├── ShapeView.kt       SystemUI 里那块覆盖层
└── HookEntry.kt       hook 入口
```

### 配置怎么跨进程传

app 和 hook 跑在两个进程：app 是「挖孔黑方块」自己，hook 在 SystemUI 里。

- **hook 里调 `getSharedPreferences` 是错的** —— 那读到的是 SystemUI 自己的 prefs，静默拿到错数据。
- Android 11+ 之后 `/data/data/<pkg>` 别的应用也进不去，直接读文件也不行。

所以走 `ConfigProvider`（exported，只返回一个形状名字符串），hook 用 ContentResolver 查。
实时更新走 `ContentObserver`：app 改完 `notifyChange`，hook 那边的观察者就醒了。

**`queryShapeOrNull` 查不到时返回 null 而不是默认值** —— 这点很关键。
SystemUI 启动极早，那时 app 进程还没起来，query 会失败；失败和「用户真的选了方形」
都会得到 SQUARE，混为一谈的话重启后形状就悄悄退回方形且再也纠不回来。
调用方拿到 null 就重试（实测要等到**第 6~8 次、约 9~12 秒**才应答）。

## 加新形状

1. `Shape.kt` 的枚举里加一项 + 在 `buildDrawable()` 里给出对应 Drawable
2. 完事。app 的单选框、预览、SystemUI 的覆盖层全都是从 `HoleShape.entries` 生成的

Drawable 只要在自己 bounds 里画满就行。**注意 `Drawable.draw()` 的约定是"画在 getBounds() 里"，
但 canvas 不会自动平移到 bounds 原点** —— 框架自带的 ColorDrawable 内部自己做了偏移，
手写 Drawable 必须自己加 `bounds.left/top`，否则尺寸对、位置却钉在 (0,0)。

## samples/ —— 现成的图案和生成器

```
samples/
├── fade-bar.svg    中间实心、左右平滑淡出的横条（260×82）
├── star.svg        圆角五角星（145×145，内切圆 43px）
└── make-star.py    五角星生成器，改参数重跑就行
```

### make-star.py

```bash
python3 -I make-star.py                            # 默认：0.47 内比、尖角大圆
python3 -I make-star.py --tip 0.22                 # 角再圆一点
python3 -I make-star.py --inner-ratio 0.40         # 星瘦一点（自动放大补偿）
python3 -I make-star.py --out my-star.svg
```

会打印「app 里的大小填多少能 1:1 渲染」，抄进去就行。

**两条关键数学**（脚本注释里也写了）：

**① 圆角要是真圆弧，控制点不能随手取。**
顶点内角 θ、圆角半径 t：

```
切点到顶点的距离    d = t / tan(θ/2)
弧的转角            Δ = π − θ
控制点离切点的距离   k = (4/3)·tan(Δ/4)·t
```

从切点画三次贝塞尔，控制点沿「切点→顶点」方向偏移 `k`。少了那个系数，弧会鼓或者瘪。
（`d` 不能超过相邻边长的一半，否则圆角把自己吃掉。）

**② 星的内切圆由凹角决定，不是尖角。**
轮廓上离中心最近的地方是凹角顶点。凹角一圆，边界整体往中心收，内切圆半径大约掉到
`r − 2.24·t_valley`。所以**凹角几乎不能圆** —— 要盖住半径 `r_hole` 的洞，必须
`r − 2.24·t_valley ≥ r_hole`，只能靠加大外半径补偿。本项目要盖 ⌀82 的挖孔，
所以脚本的 `--inscribed` 默认 43。

尖角就没这个顾虑，它离中心远，想圆多少圆多少 —— 正好也是「别搞成尖尖的」想要的。

### fade-bar.svg 怎么调

| 想改什么 | 改哪儿 |
|---|---|
| 整体长度 | `<rect>` 的 `width` 和渐变的 `x2`（offset 记得按新宽度重算 = x ÷ 宽度） |
| 实心段宽度 | 中间那两个 `offset`。左缘 x = 中心 − 宽度/2 |
| 淡出手感 | 尾部那个 `alpha(s) = 3s² − 2s³` 换成别的曲线重新采样 |
| 两端圆角 | `<rect>` 加 `rx`/`ry`（上限 = 半个高度），去掉就是直角 |

`alpha` 一律用 smoothstep 而不是线性 —— 线性末端斜率不为零，alpha 撞到 0 会留下一条
看得出来的终止线。

## 踩过的六个坑

**1. 窗口类型不能是 2038。**
第一版用 `TYPE_APPLICATION_OVERLAY`(2038)，层序只有 111000，被控制中心盖住、锁屏也看不到。
实测层序表：

```
111000  HoleSquare(2038)      ← 太低
151000  StatusBar
171000  NotificationShade    ← 控制中心是独立窗口，比 2038 高一整档
561000  NavigationBar
571000  ScreenDecorOverlay   ← 系统画圆角/挖孔装饰的窗口
```

`ScreenDecorOverlay` 是 `ty=NAVIGATION_BAR_PANEL`(2024)，flags 是
`NOT_FOCUSABLE NOT_TOUCHABLE NOT_TOUCH_MODAL LAYOUT_IN_SCREEN`，私有 flags 有
`IS_ROUNDED_CORNERS_OVERLAY` —— 它画的屏幕圆角**在锁屏上照样可见**，证明这层在键盘锁之上。
所以改用 2024，提到 571000，控制中心和锁屏一起解决。

查层序：

```bash
adb shell dumpsys window windows | awk '
/^  Window #[0-9]+ Window\{/ { n=$0; sub(/.*u0 /,"",n); sub(/\}:.*/,"",n) }
/mBaseLayer=/ { if (match($0,/mBaseLayer=[0-9-]+/)) printf "%s %s\n", substr($0,RSTART+11,RLENGTH-11), n }
' | sort -n
```

**2. `view.background = drawable` 换形状时画面纹丝不动。**
日志明明打了「形状已切到三角形」，屏幕上还是方块。改成自己 `onDraw` 画就正常了。

**3. 更阴的：不清画布，旧图形赖着不走。**
改成自绘之后，View 没有背景 → 框架不认为它 opaque → **硬件渲染器复用上一帧的缓冲**，
三角形是画在旧的方块上面的，看着还是方块。
修法两件套：窗口 `format` 改 `TRANSLUCENT`，`onDraw` 开头 `canvas.drawColor(TRANSPARENT, CLEAR)`。
（窗口本来就该是半透明的 —— 三角形盖不满 72×72，没盖到的地方得透过去看见后面。）

**4. 丢到 Handler 上跑的代码，异常会被吞。**
`install()` 一开始没包 try，异常被 Handler 吃掉，表现为「窗口加上了、日志没了、
后面的 ContentObserver 也没注册」，现场一片安静。现在整个包在 try 里并打日志。

**5. `RadioGroup` 只认直接子 View。**
它靠 `PassThroughHierarchyChangeListener` 给子 View 挂内部监听器，而**只对直接是
`RadioButton` 的子 View 生效**。为了在一行里并排放「改名/删除」按钮，把 RadioButton
包进 LinearLayout 之后，点单选框只会让按钮自己亮起来，`RadioGroup` 完全不知情 ——
`OnCheckedChangedListener` 不触发，旧的选中项也不会取消（可能两个同时亮）。

修法两条缺一不可：
- 给每个 RadioButton 自己挂 `setOnClickListener { shapeGroup.check(id) }`
- 重建列表后调一次 `shapeGroup.check(当前选中项)` —— 光设 `isChecked = true` 只让按钮亮，
  `RadioGroup` 内部记的 `mCheckedId` 还是 -1，用户点下一个时它去"取消旧的"会找不到目标

**6. `check()` 只点亮按钮，真正的处理在 group 的监听器里。**
重写界面时把 `shapeGroup.setOnCheckedChangeListener` 整段丢了，结果按钮点得动、灯会亮，
**但什么都不发生** —— 选中项既不保存也不上屏。存储里是旧值、界面上亮的是新值，两边对不上。

教训：测交互不能只看「控件状态对不对」，得去查**副作用**（配置有没有真的落盘）。

## 构建

```bash
./build.sh
adb install -r build/module.apk
```

手搓构建（kotlinc + d8 + aapt2），不用 Gradle。**只需要自备 Android SDK**，其余全自动：

| 依赖 | 怎么来 |
|---|---|
| `build-tools;34.0.0` + `platforms;android-34` | 自备，用 `ANDROID_SDK_ROOT` 指向 SDK（默认 `~/Android/Sdk`） |
| Kotlin 编译器 2.4.20 | **首次构建自动从 Maven Central 下到 `./tools/`**（约 63 MB，之后走缓存） |
| Xposed API（`tools/api-82.jar`，25 KB） | 随仓库提交，不用管 |
| 签名钥匙 | 缺失时自动生成到项目根的 `debug.keystore`（已在 `.gitignore` 里） |

想用自己的钥匙设 `KEYSTORE`，工具链想放别处设 `TOOLS_DIR`。

**干净环境验证过**：把项目拷到空目录、不给任何工具链，直接跑 `./build.sh` 能出 APK。

没有 AndroidX —— 界面全用代码搭，不碰 R 资源（只有图标和 xposedscope 走 res/，
由 aapt2 在链接期解析）。

装完之后**需要重启一次 SystemUI** 模块才生效：

```bash
adb shell su -c 'killall com.android.systemui'
```

> ⚠️ 别连着反复执行这条 —— LSPosed 把 SystemUI 反复死亡当成系统不稳定，
> 累计 4 次会自动进入**安全模式**，届时所有 Xposed 模块全部停用直到重启。
> 只在装完模块需要生效时来一次。

## 许可证

MIT

## 验证

模块日志**不在 logcat 里**，在 LSPosed 的日志文件：

```bash
adb shell su -c 'cat $(ls -t /data/adb/lspd/log/modules_*.log | head -1)' | grep 挖孔黑方块
```

正常应该看到：

```
已挂上 com.android.systemui.SystemUIApplication#onCreate
窗口已就位 72x72 @ (596, 40)，挖孔 72x72 @ (596, 40)
形状同步为「triangle」（第 6 次尝试）
onDraw #2 shape=triangle 72x72
```

看实际效果直接截图量像素（**这块覆盖层是真实图层，会出现在截图里**）：

```bash
adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png
# 方块：(596,40)-(668,112) 全黑，5184 像素
# 三角：逐行黑像素宽度 0,8,16,...,64 线性递增，约 2520 像素
```

⚠️ 注意截图**看不到物理孔**，所以截图上三角形是干净的三角；
真机上圆的左右两侧还会从斜边鼓出来两块。预览视图画的是并集，跟真机一致。

## 失效了怎么办

- **没有「窗口已就位」** → 加窗被拒或 `SystemUIApplication` 类名变了
- **有「窗口已就位」但形状一直是默认的** → provider 没应答，看「形状同步为」那条是第几次尝试
- **形状切了但画面不变** → 回到坑 2/3，确认窗口是 TRANSLUCENT 且 onDraw 开头有清画布

反编译参考：SystemUI 的 dex 在 `/system_ext/priv-app/SystemUI/SystemUI.apk`，
挖孔相关在 `com.oplus.systemui.statusbar.util.OplusStatusBarUtils` 和
`com.oplus.systemui.statusbar.OplusScreenDecorationsExImpl`。
