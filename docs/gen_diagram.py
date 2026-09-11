# -*- coding: utf-8 -*-
"""把 .puml 渲染成 PNG —— 完全本地渲染，不上传任何内容。

本机没装 PlantUML，但 IDEA 的插件目录里自带了 plantuml jar
（qoder / lingma 插件会带）。脚本会自动去常见位置找，也可以用环境变量
PLANTUML_JAR 指定。

用法：
    python docs/gen_diagram.py docs/xxx.puml          # 生成同名 .png
    python docs/gen_diagram.py --all                  # 渲染 docs 下所有 .puml
    set PLANTUML_JAR=D:\\tools\\plantuml.jar           # 手动指定 jar
"""
import glob
import os
import subprocess
import sys

BASE = os.path.dirname(os.path.abspath(__file__))


def find_jar():
    """按优先级找 plantuml jar。"""
    env = os.environ.get("PLANTUML_JAR")
    if env and os.path.isfile(env):
        return env

    appdata = os.environ.get("APPDATA", "")
    patterns = [
        # IDEA 插件自带的（qoder / lingma / 其他）
        os.path.join(appdata, "JetBrains", "*", "plugins", "*", "lib", "plantuml*.jar"),
        os.path.join(appdata, "JetBrains", "*", "plugins", "**", "plantuml*.jar"),
        # 手动放置的位置
        os.path.join(BASE, "tools", "plantuml*.jar"),
        r"D:\tools\plantuml*.jar",
        r"E:\tools\plantuml*.jar",
    ]
    for pattern in patterns:
        hits = sorted(glob.glob(pattern, recursive=True))
        if hits:
            return hits[-1]
    return None


def render(puml_path, jar, out_override=None):
    """本地渲染：java -jar plantuml.jar -tpng -charset UTF-8 <file>"""
    cmd = ["java", "-jar", jar, "-tpng", "-charset", "UTF-8", puml_path]
    result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")

    default_out = os.path.splitext(puml_path)[0] + ".png"
    if result.returncode != 0:
        raise RuntimeError("渲染失败：{}\n{}".format(result.stdout, result.stderr))

    if out_override and os.path.abspath(out_override) != os.path.abspath(default_out):
        os.replace(default_out, out_override)
        return out_override
    return default_out


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 1

    jar = find_jar()
    if not jar:
        print("找不到 plantuml jar。请设置环境变量 PLANTUML_JAR 指向 jar 文件。")
        return 2
    print("使用 PlantUML:", jar)

    all_mode = args[0] == "--all"
    if all_mode:
        targets = [os.path.join(BASE, n) for n in sorted(os.listdir(BASE)) if n.endswith(".puml")]
        out_override = None
    else:
        targets = [args[0]]
        out_override = args[1] if len(args) > 1 else None

    failed = 0
    for src in targets:
        if not os.path.exists(src):
            print("跳过（不存在）:", src)
            continue
        try:
            dst = render(src, jar, out_override)
            print("已渲染: {} -> {} ({} KB)".format(
                os.path.basename(src), os.path.basename(dst),
                os.path.getsize(dst) // 1024))
        except Exception as ex:
            failed += 1
            print("失败:", os.path.basename(src))
            print(str(ex)[:800])
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
