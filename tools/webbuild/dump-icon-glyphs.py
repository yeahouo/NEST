"""从随包的 Material Symbols 子集字体里导出它真正支持的图标名。

两个坑，都踩过：
1) Google 交付的子集字体里字形名是 uniE000 这种码位名，真实图标名只存在于 GSUB 的
   连字（rlig）规则里 —— 图标名就是连字输入串。所以不能看 getGlyphOrder()，要读 GSUB。
2) 连字分量给出的是「字形名」而不是字符：'arrow_back' 的分量字形名是
   a / r / r / o / w / underscore / b / a / c / k，直接拼字形名会得到
   "arrowunderscoreback"。必须把字形名换成它代表的字符（用 cmap 反查，
   再兜住 digit_zero..digit_nine 这类 cmap 里没有的别名）。

产物 tools/webbuild/icon-glyphs.txt 是 tools/webbuild/check-icon-font.mjs 的输入。
字体每次重新生成后都要重跑本脚本。

用法：python tools/webbuild/dump-icon-glyphs.py
"""
import re
import sys
from pathlib import Path

from fontTools.ttLib import TTFont

HERE = Path(__file__).resolve().parent
FONT = HERE.parents[1] / "src" / "main" / "resources" / "static" / "fonts" / "material-symbols-outlined.woff2"
OUT = HERE / "icon-glyphs.txt"

DIGIT_WORDS = {
    "zero": "0", "one": "1", "two": "2", "three": "3", "four": "4",
    "five": "5", "six": "6", "seven": "7", "eight": "8", "nine": "9",
}


def char_of(glyph_name: str, rev: dict[str, str]) -> str | None:
    """字形名 -> 它在连字里代表的字符。"""
    if glyph_name in rev:
        return rev[glyph_name]
    m = re.fullmatch(r"digit_([a-z]+)", glyph_name)
    if m and m.group(1) in DIGIT_WORDS:
        return DIGIT_WORDS[m.group(1)]
    if glyph_name in DIGIT_WORDS:
        return DIGIT_WORDS[glyph_name]
    if len(glyph_name) == 1:
        return glyph_name
    return None


def ligature_names(path: Path) -> tuple[list[str], list[str]]:
    font = TTFont(str(path))
    # 字符 -> 字形名的反查（只取 BMP 里真正是「一个字符」的映射）
    rev: dict[str, str] = {}
    for cp, glyph in font.getBestCmap().items():
        if cp < 0x10000:
            # 同一个字形常常同时被 'A' 和 'a' 映射到（大写码位先遍历到），
            # 而图标名一律小写，所以优先保留小写映射。
            cur = rev.get(glyph)
            ch = chr(cp)
            if cur is None or (ch.islower() and not cur.islower()):
                rev[glyph] = ch

    gsub = font["GSUB"].table
    names: set[str] = set()
    unknown: set[str] = set()
    for lookup in gsub.LookupList.Lookup:
        for st in lookup.SubTable:
            # Google 把连字包在 LookupType 7（Extension）里，真类型在 ExtSubTable
            if st.__class__.__name__ == "ExtensionSubst":
                st = st.ExtSubTable
            if st.__class__.__name__ != "LigatureSubst":
                continue
            for first, ligatures in st.ligatures.items():
                for lig in ligatures:
                    chars = [char_of(first, rev)]
                    chars += [char_of(c, rev) for c in lig.Component]
                    if any(c is None for c in chars):
                        unknown.update(c for c in [first, *lig.Component] if char_of(c, rev) is None)
                        continue
                    names.add("".join(chars))
    return sorted(names), sorted(unknown)


def main() -> int:
    if not FONT.exists():
        print(f"找不到字体：{FONT}", file=sys.stderr)
        return 2
    names, unknown = ligature_names(FONT)
    OUT.write_text("\n".join(names) + "\n", encoding="utf-8")
    print(f"{FONT.name}: {len(names)} 个图标名 -> {OUT.name}")
    if unknown:
        print(f"⚠ {len(unknown)} 个字形名无法换成字符（已跳过）：{' '.join(unknown[:20])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
