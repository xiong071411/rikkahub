#!/usr/bin/env python3
"""核对全部发布包，生成按实际 APK 内容计算的分发清单。仅使用标准库与 Android SDK 工具。"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path

CERTIFICATE = "555c00f6a98cf7bb8522ea1c6f623deba83e1ecd64a7bd1a86794c9666ef5c8a"
PACKAGES = {
    "github": "me.rerere.rikkahub",
    "coexist": "me.rerere.rikkahub.coexist",
    "coexistNormal": "me.rerere.rikkahub.coexist.normal",
}
ABIS = ("armeabi-v7a", "arm64-v8a", "universal")


def verify(root: Path, build_tools: Path, version_code: int, version_name: str, commit: str = "unknown") -> list[dict]:
    aapt = build_tools / ("aapt2.exe" if (build_tools / "aapt2.exe").exists() else "aapt2")
    signer = build_tools / "lib/apksigner.jar"
    records, dex_hashes = [], {}
    expected_files = {f"app-{flavor}-{abi}-release.apk" for flavor in PACKAGES for abi in ABIS}
    actual_files = {apk.name for flavor in PACKAGES for apk in (root / flavor / "release").glob("*.apk")}
    if actual_files != expected_files:
        raise ValueError(f"发布包集合不匹配：缺少 {sorted(expected_files - actual_files)}，多出 {sorted(actual_files - expected_files)}")
    for flavor, package in PACKAGES.items():
        for abi in ABIS:
            filename = f"app-{flavor}-{abi}-release.apk"
            source = Path(flavor) / "release" / filename
            apk = root / source
            if not apk.is_file():
                raise ValueError(f"缺少发布包：{source}")
            badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True, encoding="utf-8")
            package_line = next(line for line in badging.splitlines() if line.startswith("package:"))
            metadata = dict(re.findall(r"(\w+)='([^']*)'", package_line))
            if (metadata["name"], metadata["versionCode"], metadata["versionName"]) != (package, str(version_code), version_name):
                raise ValueError(f"包名或版本不匹配：{source}: {package_line}")
            if "application-debuggable" in badging:
                raise ValueError(f"不能发布 Debug 包：{source}")
            certificate = subprocess.check_output(["java", "-jar", str(signer), "verify", "--print-certs", str(apk)], text=True)
            if f"certificate SHA-256 digest: {CERTIFICATE}" not in certificate:
                raise ValueError(f"签名与已发布版本不一致：{source}")
            with zipfile.ZipFile(apk) as archive:
                names = archive.namelist()
                actual_abis = {name.split("/")[1] for name in names if name.startswith("lib/") and name.endswith(".so")}
                expected_abis = set(ABIS[:-1]) if abi == "universal" else {abi}
                if actual_abis != expected_abis:
                    raise ValueError(f"架构不匹配：{source}: {actual_abis}")
                for native_abi in expected_abis:
                    library = archive.read(f"lib/{native_abi}/libsimple.so")
                    if library[:4] != b"\x7fELF" or library[4] != (1 if native_abi == "armeabi-v7a" else 2):
                        raise ValueError(f"分词库架构错误：{source}: {native_abi}")
                dex = [archive.read(name) for name in sorted(names) if name.endswith(".dex")]
                if not any(b"app_preferences_v2" in data for data in dex):
                    raise ValueError(f"缺少本次设置同步修复代码：{source}")
                for marker in (b"chat_input_collapse_button", b"chat_input_expand_button"):
                    if not any(marker in data for data in dex):
                        raise ValueError(f"缺少输入区折叠代码：{source}: {marker.decode()}")
                dex_hashes[(flavor, abi)] = [hashlib.sha256(data).hexdigest() for data in dex]
            record = {
                "source": source.as_posix(), "filename": filename, "flavor": flavor, "abi": abi,
                "package": package, "versionCode": version_code, "versionName": version_name,
                "size": apk.stat().st_size, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                "certificateSha256": CERTIFICATE, "sourceCommit": commit,
                "versioned_filename": f"rikkahub-v{version_name}-build{version_code}-{flavor}-{abi}.apk",
            }
            records.append(record)
            print(f"VERIFIED {filename} version={version_code} signature=matched", flush=True)
    for flavor in PACKAGES:
        for abi in ABIS[:-1]:
            if dex_hashes[(flavor, abi)] != dex_hashes[(flavor, "universal")]:
                raise ValueError(f"同变体拆分包与通用包代码不同：{flavor}/{abi}")
    return records


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--build-tools", required=True, type=Path)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--commit", default="unknown")
    args = parser.parse_args()
    records = verify(args.root, args.build_tools, args.version_code, args.version_name, args.commit)
    manifest = args.root / f"rikkahub-v{args.version_name}-build{args.version_code}-manifest.json"
    manifest.write_text(json.dumps(records, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    checksum = args.root / f"SHA256SUMS-v{args.version_name}-build{args.version_code}.txt"
    checksum.write_text("".join(row["sha256"] + "  " + row["versioned_filename"] + "\n" for row in records), encoding="utf-8")
    print(f"ALL_RELEASE_APKS_VERIFIED={len(records)}")


if __name__ == "__main__":
    main()
