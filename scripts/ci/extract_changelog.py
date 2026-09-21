#!/usr/bin/env python3
"""
从 CHANGELOG.md 中提取指定 tag 对应的更新日志段落。
"""
import argparse
import os
import re
import sys

def extract_section(changelog_path, tag):
    if not os.path.exists(changelog_path):
        print(f"[-] 警告: {changelog_path} 不存在，使用默认说明")
        return f"## WeType-Enhance {tag}\n\n详见版本提交记录。\n"

    # 规范化 tag 名称（去除开头的 v 进行匹配）
    raw_tag = tag.strip()
    if raw_tag.startswith("v") or raw_tag.startswith("V"):
        raw_tag_no_v = raw_tag[1:]
    else:
        raw_tag_no_v = raw_tag

    # 严格匹配版本号（避免 18key 错误匹配 18key.1）
    # 支持形如: ## [v1.28.4-18key.1] - 日期 或 ## v1.28.4-18key.1 - 日期
    pattern = re.compile(
        rf"^##\s+(?:\[v?{re.escape(raw_tag_no_v)}\]|v?{re.escape(raw_tag_no_v)}(?=\s|\]|$))",
        re.IGNORECASE
    )

    with open(changelog_path, "r", encoding="utf-8") as f:
        content_lines = f.readlines()

    found = False
    extracted = []

    for line in content_lines:
        stripped = line.strip()
        if not found:
            if pattern.match(stripped):
                found = True
                extracted.append(line)
        else:
            # 遇到下一个版本二级标题 ## 则结束提取
            if stripped.startswith("## "):
                break
            extracted.append(line)

    if not found or not extracted:
        print(f"[-] 未在 {changelog_path} 中找到 tag: {tag} 的对应段落，采用回退说明")
        return f"## WeType-Enhance {tag}\n\n详见版本提交历史与发布资产。\n"

    # 去除末尾多余空行与水平分隔线
    text = "".join(extracted).strip()
    text = re.sub(r"\n---\s*$", "", text).strip()
    return text + "\n"

def main():
    parser = argparse.ArgumentParser(description="提取 CHANGELOG.md 中指定 tag 的更新说明")
    parser.add_argument("--tag", required=True, help="Release Tag (如 v1.28.4-18key.1)")
    parser.add_argument("--changelog", default="CHANGELOG.md", help="CHANGELOG.md 文件路径")
    parser.add_argument("--output", help="输出文件路径 (留空则输出到 stdout)")
    args = parser.parse_args()

    notes = extract_section(args.changelog, args.tag)
    if args.output:
        os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(notes)
        print(f"[+] 成功提取更新日志至: {args.output}")
    else:
        print(notes)

if __name__ == "__main__":
    main()
