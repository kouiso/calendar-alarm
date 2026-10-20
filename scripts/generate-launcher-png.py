#!/usr/bin/env python3
# ランチャーアイコンの PNG (mipmap-*dpi) を、アダプティブアイコンの前景 drawable と背景色から作り直す。
# 端末 (API 26+) はアダプティブアイコンを使う。PNG はアダプティブを描けない配布サービスの一覧表示 (テスト配信など) 向け。
# 前景か背景色を変えたら: python3 scripts/generate-launcher-png.py androidApp/src/main/res  (rsvg-convert が要る)
import re, subprocess, sys, os
res = sys.argv[1]
fg = open(f"{res}/drawable/ic_launcher_foreground.xml").read()
bg = re.search(r'name="ic_launcher_background">(#[0-9A-Fa-f]{6})<', open(f"{res}/values/ic_launcher_background.xml").read()).group(1)
g = re.search(r'<group([^>]*)>', fg).group(1)
attr = lambda k, d: float(re.search(rf'android:{k}="([\d.]+)"', g).group(1)) if re.search(rf'android:{k}=', g) else d
sx, sy, tx, ty = attr("scaleX", 1), attr("scaleY", 1), attr("translateX", 0), attr("translateY", 0)
path = re.search(r'android:pathData="([^"]+)"', fg).group(1)
fill = re.search(r'android:fillColor="(#[0-9A-Fa-f]{6})"', fg).group(1)
def svg(round_mask):
    clip = '<circle cx="54" cy="54" r="36"/>' if round_mask else '<rect x="18" y="18" width="72" height="72"/>'
    # 端末で見える 72dp (18..90) を切り出す。前景・背景の位置関係はアダプティブアイコンそのまま
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="72" height="72" viewBox="18 18 72 72">
<defs><clipPath id="m">{clip}</clipPath></defs><g clip-path="url(#m)"><rect width="108" height="108" fill="{bg}"/>
<g transform="translate({tx},{ty}) scale({sx},{sy})"><path fill="{fill}" d="{path}"/></g></g>
</svg>'''
for dens, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    d = f"{res}/mipmap-{dens}"; os.makedirs(d, exist_ok=True)
    for name, rnd in (("ic_launcher", False), ("ic_launcher_round", True)):
        subprocess.run(["rsvg-convert", "-w", str(px), "-h", str(px), "-o", f"{d}/{name}.png"], input=svg(rnd).encode(), check=True)
print(f"bg={bg} translate=({tx},{ty}) scale=({sx},{sy})")
