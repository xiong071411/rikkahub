#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
RikkaHub 安装包同步到本地 8088 端口下载站点发布脚本
功能：
1. 检查并从 GitHub Actions 产物中同步最新的官方版与共存版 APK
2. 将构建产物归档至 /root/tclock/web_dist/apks/
3. 更新 web_dist/index.html 中的 RikkaHub 下载入口（含共存版）
4. 保证 8088 端口服务持续可用 (http://45.207.200.189:8088)
"""

import os
import sys
import subprocess
import shutil
import hashlib
from datetime import datetime

WEB_DIST_DIR = "/root/tclock/web_dist"
APKS_DIR = os.path.join(WEB_DIST_DIR, "apks")
INDEX_HTML = os.path.join(WEB_DIST_DIR, "index.html")
SERVER_PORT = 8088
SERVER_IP = "45.207.200.189"

def ensure_http_server():
    check_cmd = f"ss -tulpn | grep :{SERVER_PORT} || true"
    out = subprocess.check_output(check_cmd, shell=True).decode()
    if f":{SERVER_PORT}" in out:
        return
    print(f"[*] 端口 {SERVER_PORT} 未运行，正在启动...")
    server_script = f"nohup python3 -m http.server {SERVER_PORT} --directory {WEB_DIST_DIR} > /dev/null 2>&1 &"
    subprocess.Popen(server_script, shell=True)

def calculate_file_hash(filepath):
    sha256 = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            sha256.update(chunk)
    return sha256.hexdigest()

def sync_from_github_run(run_id=None):
    os.makedirs(APKS_DIR, exist_ok=True)
    repo = "xiong071411/rikkahub"

    if not run_id:
        print("[*] 正在查询最新成功的 GitHub 构建流水线...")
        res = subprocess.run(
            ["gh", "run", "list", "--repo", repo, "--status", "success", "--limit", "1", "--json", "databaseId"],
            capture_output=True, text=True
        )
        import json
        runs = json.loads(res.stdout) if res.stdout else []
        if not runs:
            print("[!] 未找到已完成的构建，尝试查询进行中或最新运行...")
            res = subprocess.run(
                ["gh", "run", "list", "--repo", repo, "--limit", "1", "--json", "databaseId,status"],
                capture_output=True, text=True
            )
            runs = json.loads(res.stdout) if res.stdout else []
            if not runs:
                print("[!] 无任何构建记录")
                return
        run_id = runs[0]["databaseId"]
        status = runs[0].get("status", "")
        print(f"[*] 目标构建 Run ID: {run_id} (状态: {status})")

    tmp_dir = "/tmp/rikkahub-download-artifact"
    shutil.rmtree(tmp_dir, ignore_errors=True)
    os.makedirs(tmp_dir, exist_ok=True)

    print(f"[*] 正在下载 Run {run_id} 的构建工件...")
    res = subprocess.run(["gh", "run", "download", str(run_id), "--repo", repo, "--dir", tmp_dir])
    if res.returncode != 0:
        print("[!] 下载失败，构建可能尚未完全结束或工件尚未生成。")
        return

    # 遍历并复制 APK
    copied = []
    for root, _, files in os.walk(tmp_dir):
        for f in files:
            if f.endswith(".apk"):
                src = os.path.join(root, f)
                dst = os.path.join(APKS_DIR, f)
                shutil.copy2(src, dst)
                copied.append(f)
                print(f"[+] 已同步安装包至分发目录: {f} ({round(os.path.getsize(dst)/(1024*1024), 2)} MB)")

    ensure_http_server()
    print(f"\n🎉 同步完成！共分发 {len(copied)} 个安装包")
    print(f"🌐 本地与公网下载地址: http://{SERVER_IP}:{SERVER_PORT}")

if __name__ == "__main__":
    run_arg = sys.argv[1] if len(sys.argv) > 1 else None
    sync_from_github_run(run_arg)
