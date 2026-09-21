#!/usr/bin/env python3
"""
格式化与更新 GitHub Release 说明文档。
用于在发布模块、上传免 Root 修补包或官方底包更新时生成清晰规范的 Release Notes，
并详细说明各 APK 类型的区别、适用人群与使用指南。
"""
import argparse
import datetime
import os
import re
import sys

def format_download_guide(official_ver, mod_ver, apk_name=None):
    if not apk_name:
        apk_name = f"WeChat_Keyboard-v{official_ver}-18key-enhanced-v{mod_ver}.apk"
    module_apk_name = f"WeType_Enhance-v{mod_ver}-release.apk"

    return f"""
---
### 📥 安装包类型与选择指南 (Download & Installation Guide)

本 Release 提供以下两种形态的安装包，请根据您的设备环境与需求选择下载：

| 安装包类型 | 文件命名格式 | 体积 | 适用人群 | 使用方式与说明 |
| :--- | :--- | :--- | :--- | :--- |
| **📦 免 Root 独立整合安装包**<br>*(推荐大多数用户)* | `{apk_name}` | 约 400MB | **无需 Root**、未安装 LSPosed 框架的所有 Android 用户 | **直接安装即可使用**。<br>基于微信输入法官方底包（`v{official_ver}`），通过 LSPatch 框架内嵌 18 键增强模块并重签名。<br>*(⚠️ 注意：若手机上已安装官方原版微信输入法，因签名不同需先备份个人词库并卸载官方版，再安装本整合包)* |
| **🧩 独立 Xposed / LSPosed 模块** | `{module_apk_name}` | 约 5MB | **已 Root** 并且已激活 **LSPosed** 框架的高级用户 | **配合官方原版微信输入法使用**。<br>仅包含增强模块代码，安装后在 LSPosed 作用域中勾选“微信输入法 (com.tencent.wetype)”，强行停止微信输入法进程即可生效。支持官方版后续无缝覆盖升级。 |
"""

def format_official_update_section(official_ver, official_title, mod_ver, apk_name, changelog_text):
    now_str = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).strftime("%Y-%m-%d %H:%M:%S (UTC+8)")
    cl_content = changelog_text.strip() if changelog_text else "- 官方常规体验优化与问题修复"
    guide = format_download_guide(official_ver, mod_ver, apk_name)
    return f"""{guide}
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

    # 清除旧的指南或底包更新段，避免重复堆叠
    markers = [
        "### 📥 安装包类型与选择指南",
        "### 📥 下载与安装指南",
        "### 📦 免 Root 独立安装包",
        "### 🔄 微信输入法官方底包自动更新通知",
    ]
    for marker in markers:
        if marker in current_body:
            idx = current_body.find(marker)
            sep_idx = current_body.rfind("---", 0, idx)
            if sep_idx != -1:
                current_body = current_body[:sep_idx].strip()
            else:
                current_body = current_body[:idx].strip()

    if args.mode == "append-patched":
        section = format_download_guide(args.official_ver, args.mod_ver, args.apk_name)
        new_body = current_body + "\n" + section if current_body else section.strip()

    elif args.mode == "official-update":
        cl_text = ""
        if args.changelog_file and os.path.exists(args.changelog_file):
            with open(args.changelog_file, "r", encoding="utf-8") as f:
                cl_text = f.read().strip()

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
