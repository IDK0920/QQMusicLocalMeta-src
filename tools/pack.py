#!/usr/bin/env python3
"""把 aapt2 产出的 base.apk 与 classes.dex / assets 合并成未签名 APK。
输出文件必须不存在（本环境已存在的文件不允许就地改写）。"""
import os
import sys
import zipfile


def main() -> int:
    if len(sys.argv) != 5:
        print("用法: pack.py <base.apk> <out.apk> <classes.dex> <assets_dir>", file=sys.stderr)
        return 2
    base, out, dex, assets_dir = sys.argv[1:5]
    if os.path.exists(out):
        os.remove(out)
    with zipfile.ZipFile(base) as zin, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            if info.filename == "classes.dex":
                continue
            zout.writestr(info, zin.read(info.filename))
        zout.write(dex, "classes.dex")
        for root, _dirs, files in os.walk(assets_dir):
            for name in files:
                full = os.path.join(root, name)
                rel = os.path.relpath(full, os.path.dirname(assets_dir))
                zout.write(full, rel.replace(os.sep, "/"))
    with zipfile.ZipFile(out) as z:
        names = z.namelist()
    print("    打包完成 %d 项: %s" % (len(names), ", ".join(sorted(names)[:8])))
    for need in ("classes.dex", "assets/xposed_init"):
        if need not in names:
            print("[x] 缺少 %s" % need, file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
