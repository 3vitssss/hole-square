#!/usr/bin/env python3
"""圆角五角星生成器。用法: python3 -I make-star.py [参数]

生成一段 SVG：五个角都是**圆弧**（不是尖的），整体纯黑填充。

    python3 -I make-star.py                          # 默认参数
    python3 -I make-star.py --tip 0.22 --out x.svg    # 角再圆一点
    python3 -I make-star.py --inner-ratio 0.40        # 星瘦一点（会自动放大补偿）

═══ 两条关键数学 ═══

【1】圆角怎么画才是真圆弧

    对每个顶点，内角 θ、圆角半径 t：
        切点到顶点的距离   d = t / tan(θ/2)
        弧的转角           Δ = π − θ
        控制点离切点的距离  k = (4/3)·tan(Δ/4)·t

    然后从切点画三次贝塞尔，控制点沿"切点→顶点"方向偏移 k。
    k 那个系数是**圆弧的贝塞尔标准近似**，不是随手取的 —— 少了它弧会鼓/瘪。

    注意 d 不能超过相邻边长的一半，否则圆角会把自己吃掉。

【2】五角星的内切圆由**凹角**决定，不是尖角

    轮廓上离中心最近的地方是凹角顶点。凹角一圆，边界整体往中心收，
    内切圆半径大约掉到  r − 2.24·t   （t 是凹角的圆角半径）。

    这意味着：**凹角几乎不能圆**。想盖住一个半径为 r_hole 的圆洞，
    必须保证  r − 2.24·t_valley ≥ r_hole，所以凹角只能给很小的圆角，
    剩下的都得靠加大外半径 R 来补偿。

    本项目就是拿它盖那个 ⌀82 的挖孔，所以 --inscribed 默认 43（=41+余量）。

═══ 输出 ═══

    视野是**正方形、中心正好是星心** —— 这样放进 hole-square 里形状会正对在挖孔上。
    标准输出会打印「大小设多少 px 可以 1:1 渲染」，把那句话里的值填进 app 的大小框。
"""

import argparse
import math

# ───────── 向量小工具 ─────────

def sub(a, b): return (a[0] - b[0], a[1] - b[1])
def add(a, b): return (a[0] + b[0], a[1] + b[1])
def mul(a, k): return (a[0] * k, a[1] * k)
def dot(a, b): return a[0] * b[0] + a[1] * b[1]

def unit(a):
    l = math.hypot(*a)
    return (a[0] / l, a[1] / l)

def dist(a, b): return math.hypot(a[0] - b[0], a[1] - b[1])


def star_vertices(points, outer_r, inner_r):
    """顶点从正上方开始，交替外/内。返回 [(坐标, 圆角半径), ...]"""
    verts = []
    for i in range(2 * points):
        ang = math.radians(-90 + i * (360 / (2 * points)))
        rad = outer_r if i % 2 == 0 else inner_r
        verts.append((rad * math.cos(ang), rad * math.sin(ang)))
    return verts


def round_corners(verts, radii):
    """把折线顶点换成圆弧。返回 [('C', P0, C1, C2, P1), ...]，每段自带起点。

    这是通用的"圆角多边形"做法，换个顶点表就能用在别的形状上。
    """
    n = len(verts)
    segs = []
    for i in range(n):
        V = verts[i]
        P = verts[(i - 1) % n]
        N = verts[(i + 1) % n]
        t = radii[i]

        u = unit(sub(P, V))
        w = unit(sub(N, V))
        ang = math.acos(max(-1.0, min(1.0, dot(u, w))))     # 内角
        if ang < 1e-6 or t <= 0:
            segs.append(('L', V))
            continue

        d = t / math.tan(ang / 2)
        # 圆角不能吃掉整条边
        d = min(d, dist(V, P) * 0.45, dist(V, N) * 0.45)
        t_eff = d * math.tan(ang / 2)

        t1 = add(V, mul(u, d))          # 入边上的切点
        t2 = add(V, mul(w, d))          # 出边上的切点
        k = (4 / 3) * math.tan((math.pi - ang) / 4) * t_eff
        c1 = add(t1, mul(unit(sub(V, t1)), k))
        c2 = add(t2, mul(unit(sub(V, t2)), k))
        segs.append(('C', t1, c1, c2, t2))
    return segs


def sample(segs, steps=64):
    """把线段/贝塞尔打成密集点，用来量尺寸。"""
    pts = []
    for s in segs:
        if s[0] == 'L':
            pts.append(s[1])
            continue
        _, p0, c1, c2, p1 = s
        for j in range(steps + 1):
            q = j / steps
            m = 1 - q
            pts.append((
                m**3 * p0[0] + 3 * m * m * q * c1[0] + 3 * m * q * q * c2[0] + q**3 * p1[0],
                m**3 * p0[1] + 3 * m * m * q * c1[1] + 3 * m * q * q * c2[1] + q**3 * p1[1],
            ))
    return pts


def build(outer_r, inner_r, tip_frac, valley_frac, points):
    verts = star_vertices(points, outer_r, inner_r)
    radii = [outer_r * tip_frac if i % 2 == 0 else outer_r * valley_frac
             for i in range(len(verts))]
    return round_corners(verts, radii)


def main():
    ap = argparse.ArgumentParser(description='生成圆角五角星 SVG')
    ap.add_argument('--inner-ratio', type=float, default=0.47,
                    help='内半径/外半径。0.382 是经典五角星（很瘦），0.47 更饱满。默认 0.47')
    ap.add_argument('--tip', type=float, default=0.16,
                    help='尖角圆角半径 ÷ 外半径。越大角越圆。默认 0.16')
    ap.add_argument('--valley', type=float, default=0.035,
                    help='凹角圆角半径 ÷ 外半径。**别调大**，它直接吃掉内切圆。默认 0.035')
    ap.add_argument('--inscribed', type=float, default=43.0,
                    help='要保证的内切圆半径(px)。盖 ⌀82 的挖孔就给 43。默认 43')
    ap.add_argument('--points', type=int, default=5, help='角的数量，默认 5')
    ap.add_argument('--out', default='star.svg', help='输出文件名')
    args = ap.parse_args()

    if args.inner_ratio >= 1:
        ap.error('--inner-ratio 必须小于 1')

    def make(r):
        return build(r, r * args.inner_ratio, args.tip, args.valley, args.points)

    # 内切圆半径随 R 线性缩放，所以先用 R=100 量一次再换算
    probe = 100.0
    r_probe = min(math.hypot(*p) for p in sample(make(probe)))
    if r_probe <= 1e-6:
        ap.error('凹角圆角太大，形状塌了 —— 调小 --valley')
    outer_r = probe * args.inscribed / r_probe

    segs = make(outer_r)
    pts = sample(segs)
    inscribed = min(math.hypot(*p) for p in pts)

    half = max(max(abs(p[0]) for p in pts), max(abs(p[1]) for p in pts))
    side = 2 * half

    # 多项式太多小数位没意义，两位够了
    def fmt(p):
        return f"{p[0] + half:.2f},{p[1] + half:.2f}"

    d = [f"M {fmt(segs[0][1])}"]
    for s in segs:
        if s[0] == 'L':
            d.append(f"L {fmt(s[1])}")
        else:
            _, _, c1, c2, p1 = s
            d.append(f"C {fmt(c1)} {fmt(c2)} {fmt(p1)}")
    d.append("Z")

    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="{side:.0f}" height="{side:.0f}" viewBox="0 0 {side:.2f} {side:.2f}">
  <!--
    {args.points} 角圆角星。纯黑填充，无渐变。

    角是圆弧不是尖的：每个顶点按内角 θ 取切点 d = t/tan(θ/2)，
    圆弧再用三次贝塞尔逼近，控制点离切点 (4/3)·tan(Δ/4)·t（Δ = π − θ）。

    尖角圆角 {outer_r*args.tip:.2f}px（{args.tip}×R）
    凹角圆角 {outer_r*args.valley:.2f}px（{args.valley}×R）—— 凹角决定内切圆，只能给小的
    外半径 {outer_r:.2f}，内/外 = {args.inner_ratio}
    内切圆半径 {inscribed:.2f}px

    视野是正方形、中心就是星心，所以形状会正对在挖孔上。
    大小设 {side:.0f}px 时按 1:1 渲染。

    生成命令：make-star.py --inner-ratio {args.inner_ratio} --tip {args.tip} --valley {args.valley} --inscribed {args.inscribed}
  -->
  <path d="{" ".join(d)}" fill="#000000"/>
</svg>
'''
    with open(args.out, 'w', encoding='utf-8') as f:
        f.write(svg)

    print(f"已写入 {args.out}")
    print(f"  外半径        {outer_r:.2f}")
    print(f"  尖角圆角      {outer_r*args.tip:.2f}px")
    print(f"  凹角圆角      {outer_r*args.valley:.2f}px")
    print(f"  内切圆半径    {inscribed:.2f}px")
    print(f"  → app 里的「大小」填 {side:.0f}（1:1 渲染）")


if __name__ == '__main__':
    main()
