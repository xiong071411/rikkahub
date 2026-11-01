#!/usr/bin/env python3
"""校验完整构建后更新指定门户；目标路径必须显式提供，不默认操作其他项目。"""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import os
import re
import shutil
from pathlib import Path

from verify_release_apks import verify


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact-dir", required=True, type=Path)
    parser.add_argument("--build-tools", required=True, type=Path)
    parser.add_argument("--portal-dir", required=True, type=Path)
    parser.add_argument("--backup-dir", required=True, type=Path)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--summary", required=True)
    parser.add_argument("--dry-run", action="store_true", help="校验安装包与门户内容，不写入文件")
    args = parser.parse_args()
    records = verify(args.artifact_dir, args.build_tools, args.version_code, args.version_name, args.commit)
    portal = args.portal_dir.resolve()
    apks = portal / "apks"
    index = portal / "index.html"
    original = index.read_text(encoding="utf-8")
    start = original.index("<!-- RikkaHub 专区 -->")
    end = original.index('<div class="section-header">历史版本归档')
    current = original[start:end]
    current = re.sub(r"<!-- 当前原包名版 -->.*", "", current, flags=re.DOTALL)
    current = re.sub(r'<a[^>]+href="apks/rikkahub-v[^"/]+-build[0-9]+-(?:coexist|coexistNormal)-(?:arm64-v8a|x86_64)\.apk"[^>]*>.*?</a>', "", current, flags=re.DOTALL)
    current = re.sub(r"v\d+\.\d+\.\d+", f"v{args.version_name}", current)
    current = re.sub(r"Build \d+", f"Build {args.version_code}", current)
    current = re.sub(r"build\d+", f"build{args.version_code}", current)
    current, summary_count = re.subn(
        r'(<tr><td class="meta-label">全新特性:</td><td class="meta-val">).*?(</td></tr>)',
        lambda match: match.group(1) + html.escape(args.summary) + match.group(2),
        current,
    )
    if summary_count != 2:
        raise ValueError("门户模板中的变体更新摘要数量不正确")
    by_pair = {(row["flavor"], row["abi"]): row for row in records}
    for flavor in ("coexist", "coexistNormal"):
        anchor = by_pair[(flavor, "armeabi-v7a")]["versioned_filename"]
        pattern = r'(<a[^>]+href="apks/' + re.escape(anchor) + r'"[^>]*>.*?</a>)'
        extra = "".join(
            '<a class="btn" style="background-color: #238636; color: #ffffff; flex: 1; min-width: 200px;" href="apks/'
            + by_pair[(flavor, abi)]["versioned_filename"] + '">⬇ ' + label + f'（Build {args.version_code}）</a>'
            for abi, label in (("arm64-v8a", "64位 ARM 专版"),)
        )
        current, count = re.subn(pattern, lambda match: match.group(1) + "\n" + extra, current, count=1, flags=re.DOTALL)
        if count != 1:
            raise ValueError("找不到共存版的架构按钮")
    official_links = "".join(
        '<a class="btn" style="background-color: #8957e5; color: #ffffff;" href="apks/'
        + by_pair[("github", abi)]["versioned_filename"] + '">⬇ ' + label + f'（Build {args.version_code}）</a>'
        for abi, label in (("armeabi-v7a", "32位 ARM 专版"), ("arm64-v8a", "64位 ARM 专版"),
                           ("universal", "全架构通用包"))
    )
    current += (
        '<!-- 当前原包名版 -->\n<div class="card card-highlight"><div class="card-title rikka-title">'
        + f'RikkaHub v{args.version_name} (Build {args.version_code}) · 原包名 50% 微缩版</div>'
        + '<p class="meta-val">me.rerere.rikkahub</p>' + official_links + '</div>\n'
    )
    updated = original[:start] + current + original[end:]
    for row in records:
        if current.count(f'href="apks/{row["versioned_filename"]}"') != 1:
            raise ValueError("当前门户模板缺少对应下载入口，拒绝更新：" + row["versioned_filename"])
    if "x86" in current:
        raise ValueError("当前版本区域仍包含 x86 下载入口")
    # 历史包和 TClock 区域保持当前内容，仅更换 RikkaHub 当前版本区域。
    if updated[end + len(current) - len(original[start:end]):] != original[end:]:
        raise ValueError("门户归档区域发生非预期变化")
    manifest_name = f"rikkahub-v{args.version_name}-build{args.version_code}-manifest.json"
    checksum_name = f"SHA256SUMS-v{args.version_name}-build{args.version_code}.txt"
    for row in records:
        versioned = apks / row["versioned_filename"]
        if versioned.exists() and hashlib.sha256(versioned.read_bytes()).hexdigest() != row["sha256"]:
            raise ValueError("已有同名版本文件内容不同，拒绝覆盖：" + str(versioned))
    if args.dry_run:
        print(f"PORTAL_DRY_RUN_OK version={args.version_name} build={args.version_code} packages={len(records)}")
        return
    args.backup_dir.mkdir(parents=True, mode=0o700, exist_ok=False)
    (args.backup_dir / "apks").mkdir()
    shutil.copy2(index, args.backup_dir / "index.html")
    (args.backup_dir / "verified-manifest.json").write_text(json.dumps(records, indent=2), encoding="utf-8")
    by_pair = {(row["flavor"], row["abi"]): row for row in records}
    aliases = {
        "rikkahub-latest-arm32.apk": by_pair[("github", "armeabi-v7a")],
        "rikkahub-latest-watch50.apk": by_pair[("github", "armeabi-v7a")],
        "rikkahub-latest-universal.apk": by_pair[("github", "universal")],
        "app-coexist-release-universal.apk": by_pair[("coexist", "universal")],
    }
    added = []
    targets = [(row["versioned_filename"], row) for row in records] + [(row["filename"], row) for row in records] + list(aliases.items())
    for name, row in targets:
        target = apks / name
        if target.exists():
            if hashlib.sha256(target.read_bytes()).hexdigest() == row["sha256"]:
                continue
            shutil.copy2(target, args.backup_dir / "apks" / name)
        else:
            added.append("apks/" + name)
        temporary = apks / ("." + name + ".tmp")
        shutil.copyfile(args.artifact_dir / row["source"], temporary)
        temporary.chmod(0o644)
        os.replace(temporary, target)
    (apks / manifest_name).write_text(json.dumps(records, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (apks / checksum_name).write_text("".join(row["sha256"] + "  " + row["versioned_filename"] + "\n" for row in records), encoding="utf-8")
    added.extend(["apks/" + manifest_name, "apks/" + checksum_name])
    (args.backup_dir / "added-paths.json").write_text(json.dumps(added, indent=2), encoding="utf-8")
    temporary = portal / ".index.html.tmp"
    temporary.write_text(updated, encoding="utf-8")
    temporary.chmod(0o644)
    os.replace(temporary, index)
    print(f"PORTAL_UPDATED version={args.version_name} build={args.version_code} packages={len(records)}")
    print("BACKUP=" + str(args.backup_dir))


if __name__ == "__main__":
    main()
