#!/usr/bin/env python3
"""从 dexdump -d 输出里切出某个类的完整反汇编，并把 const-string 就地带上可读文本。

用法: python3 tools/dumpclass.py dex/classesN.dex 'Lcom/tencent/.../c;'
      python3 tools/dumpclass.py dex/classesN.dex 'filescanner'
依赖: .toolchain/sdk/build-tools/34.0.0/dexdump
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEXDUMP = os.path.join(ROOT, ".toolchain/sdk/build-tools/34.0.0/dexdump")


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    dex, want = sys.argv[1], sys.argv[2]
    if not os.path.isfile(dex):
        dex = os.path.join(ROOT, dex)
    if not os.path.isfile(DEXDUMP):
        print("[x] 缺少 dexdump: %s" % DEXDUMP, file=sys.stderr)
        return 1

    proc = subprocess.Popen([DEXDUMP, "-d", dex], stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, text=True, errors="replace")

    in_class = False
    cur_class = None
    printed = 0
    for line in proc.stdout:
        m = re.match(r"\s*Class descriptor\s*:\s*'(.+)'", line)
        if m:
            cur_class = m.group(1)
            in_class = want in cur_class
            if in_class:
                printed += 1
                print("\n" + "=" * 78)
            continue
        if not in_class:
            continue
        if "|" in line and "//" not in line and "const-string" in line:
            line = line.rstrip("\n") + "\n"
        sys.stdout.write(line)

    if proc.poll() is None:
        proc.terminate()
    if printed == 0:
        print("[!] 没有匹配的类: %s" % want, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
