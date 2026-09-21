#!/usr/bin/env python3
"""
格式化与更新 GitHub Release 说明文档。
用于在发布模块、上传免 Root 修补包或官方底包更新时生成清晰规范的 Release Notes。
"""
import argparse
import datetime
import os
import re
import sys

def format_patched_section(official_ver, mod_ver, apk_name):
    return f"""
---
### 📦 免 Root 独立安装包 (Standalone Patched APK)
- **文件名称**: `{apk_name}`
- **官方底包基线**: 微信输入法官方 `v{official_ver}`
- **内置增强模块**: WeType-Enhance `v{mod_ver}`
- **使用说明**: 无需 Root 权限与 LSPosed 框架，直接安装即可体验 18 键键盘布局及全部增强特性。
"""

def format_official_update_section(official_ver, official_title, mod_ver, apk_name, changelog_text):
    now_str = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).strftime("%Y-%m-%d %H:%M:%S (UTC+8)")
    cl_content = changelog_text.strip() if changelog_text else "- 官方常规体验优化与问题修复"
    return f"""
---
### 🔄 微信输入法官方底包自动更新通知 (Official Base Update)
- **巡检更新时间**: {now_str}
- **微信输入法官方版本**: `v{official_ver}` ({official_title})
- **自动修补包**: `{apk_name}`
- **集成模块基线**: 基于当前最新 Release `v{mod_ver}` 自动构建并注入
- **官方更新日志**:
{cl_content}
"""

def main():
    parser = argparse.ArgumentParser(description="格式化 GitHub Release 说明")
    parser.add_argument("--mode", choices=["append-patched", "official-update"], required=True)
    parser.add_argument("--base-notes", help="现有 Release 说明文本或文件路径")
    parser.add_argument("--official-ver", required=True, help="微信输入法官方版本号")
    parser.add_argument("--mod-ver", required=True, help="WeType-Enhance 模块版本号")
    parser.add_argument("--apk-name", required=True, help="修补后的 APK 文件名")
    parser.add_argument("--official-title", default="微信输入法官方版本更新", help="官方发布标题")
    parser.add_argument("--changelog-file", help="官方更新日志文件路径")
    parser.add_argument("--output", required=True, help="格式化后的输出文件路径")
    args = parser.parse_args()

    # 读取原有 notes
    current_body = ""
    if args.base_notes:
        if os.path.isfile(args.base_notes):
            with open(args.base_notes, "r", encoding="utf-8") as f:
                current_body = f.read()
        else:
            current_body = args.base_notes

    current_body = current_body.strip()

    if args.mode == "append-patched":
        # 避免重复追加
        marker = "### 📦 免 Root 独立安装包"
        if marker in current_body:
            # 替换旧的免 Root 说明段
            idx = current_body.find(marker)
            # 找到前置的 ---
            sep_idx = current_body.rfind("---", 0, idx)
            if sep_idx != -1:
                current_body = current_body[:sep_idx].strip()
            else:
                current_body = current_body[:idx].strip()

        section = format_patched_section(args.official_ver, args.mod_ver, args.apk_name)
        new_body = current_body + "\n" + section if current_body else section.strip()

    elif args.mode == "official-update":
        cl_text = ""
        if args.changelog_file and os.path.exists(args.changelog_file):
            with open(args.changelog_file, "r", encoding="utf-8") as f:
                cl_text = f.read().strip()

        # 避免重复追加相同的官方更新段
        marker = "### 🔄 微信输入法官方底包自动更新通知"
        if marker in current_body:
            idx = current_body.find(marker)
            sep_idx = current_body.rfind("---", 0, idx)
            if sep_idx != -1:
                current_body = current_body[:sep_idx].strip()
            else:
                current_body = current_body[:idx].strip()

        section = format_official_update_section(
            args.official_ver, args.official_title, args.mod_ver, args.apk_name, cl_text
        )
        new_body = current_body + "\n" + section if current_body else section.strip()

    os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as f:
        f.write(new_body.strip() + "\n")

    print(f"[+] Release 说明已写入: {args.output}")

if __name__ == "__main__":
    main()
