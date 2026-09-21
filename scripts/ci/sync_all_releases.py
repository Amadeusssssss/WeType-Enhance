#!/usr/bin/env python3
"""
同步与格式化所有历史及当前 GitHub Releases 的说明文档。
自动提取 CHANGELOG.md 内容，并统一注入安装包类型对比、体积差异及选用指南。
"""
import json
import os
import re
import subprocess
import sys

from extract_changelog import extract_section
from format_release_notes import format_download_guide

def run_cmd(cmd):
    res = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    return res.returncode, res.stdout, res.stderr

def sync_releases():
    print("[*] 正在查询 GitHub Releases 列表...")
    ret, stdout, stderr = run_cmd("gh release list --json tagName,name,isDraft,isPrerelease --limit 50")
    if ret != 0:
        print(f"[-] 执行 gh release list 失败: {stderr}")
        sys.exit(1)

    releases = json.loads(stdout)
    if not releases:
        print("[-] 未检索到任何 Release")
        return

    changelog_path = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "CHANGELOG.md")
    temp_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "build_work")
    os.makedirs(temp_dir, exist_ok=True)

    for rel in releases:
        tag = rel.get("tagName")
        print(f"\n[*] 处理 Release: {tag} ...")

        # 获取当前 Release 详情
        ret, view_out, _ = run_cmd(f"gh release view \"{tag}\" --json assets,body")
        if ret != 0:
            print(f"[-] 无法获取 Release {tag} 详情，跳过")
            continue

        detail = json.loads(view_out)
        assets = [a.get("name", "") for a in detail.get("assets", [])]

        patched_apk = next((a for a in assets if a.startswith("WeChat_Keyboard-") and a.endswith(".apk")), None)
        module_apk = next((a for a in assets if a.startswith("WeType_Enhance-") and a.endswith(".apk")), None)

        # 尝试从文件名解析底包版本与模块版本
        official_ver = "3.5.3"
        raw_tag = tag.lstrip("v")
        mod_ver = raw_tag

        if patched_apk:
            # 格式例: WeChat_Keyboard-v3.5.3-18key-enhanced-v1.28.4-18key.1.apk
            m = re.search(r"WeChat_Keyboard-v([\d.]+)-18key-enhanced-v([^\.]+.*)\.apk", patched_apk)
            if m:
                official_ver = m.group(1)
                mod_ver = m.group(2)
            else:
                m2 = re.search(r"WeChat_Keyboard-v([\d.]+)", patched_apk)
                if m2:
                    official_ver = m2.group(1)

        # 1. 提取 CHANGELOG.md 中该 Tag 的条目
        base_notes = extract_section(changelog_path, tag).strip()

        # 2. 生成包含 APK 对比指南的说明
        guide = format_download_guide(official_ver, mod_ver, patched_apk)
        new_notes = f"{base_notes}\n{guide}".strip() + "\n"

        temp_note_file = os.path.join(temp_dir, f"notes_{tag}.md")
        with open(temp_note_file, "w", encoding="utf-8") as f:
            f.write(new_notes)

        # 3. 更新 GitHub Release 说明
        print(f"[*] 正在更新 Release {tag} 说明...")
        edit_cmd = f"gh release edit \"{tag}\" --notes-file \"{temp_note_file}\""
        ret_edit, _, err_edit = run_cmd(edit_cmd)
        if ret_edit == 0:
            print(f"[+] 成功更新 Release {tag} 说明！")
        else:
            print(f"[-] 更新 Release {tag} 失败: {err_edit}")

if __name__ == "__main__":
    sync_releases()

