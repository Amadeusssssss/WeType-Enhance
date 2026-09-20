#!/usr/bin/env python3
"""
WeType-Enhance 自动化修补工具 (用于 CI / CD 及无头命令行环境)
使用 LSPatch 免 Root 动态注入 WeType-Enhance 增强模块并进行自签名
"""

import argparse
import os
import shutil
import subprocess
import sys
import urllib.request

LSPATCH_URL = "https://github.com/LSPosed/LSPatch/releases/download/v0.6/jar-v0.6-398-release.jar"
UBER_SIGNER_URL = "https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar"

def ensure_tool(tool_path, download_url, tool_name):
    if os.path.exists(tool_path):
        return tool_path
    os.makedirs(os.path.dirname(os.path.abspath(tool_path)), exist_ok=True)
    print(f"[*] 正在下载依赖工具 {tool_name}: {download_url} ...")
    headers = {"User-Agent": "Mozilla/5.0"}
    req = urllib.request.Request(download_url, headers=headers)
    with urllib.request.urlopen(req, timeout=60) as resp, open(tool_path, "wb") as f:
        shutil.copyfileobj(resp, f)
    print(f"[+] {tool_name} 已就绪: {tool_path}")
    return tool_path

def set_github_output(key, value):
    github_output = os.getenv("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a", encoding="utf-8") as f:
            f.write(f"{key}={value}\n")
    print(f"[Output] {key}={value}")

def main():
    parser = argparse.ArgumentParser(description="微信输入法 LSPatch 自动化修补工具")
    parser.add_argument("--base-apk", required=True, help="官方微信输入法原版 APK 路径")
    parser.add_argument("--module-apk", required=True, help="WeType-Enhance 模块 APK 路径")
    parser.add_argument("--output-apk", required=True, help="最终输出修补 APK 路径")
    parser.add_argument("--lspatch-jar", help="lspatch.jar 路径，若未提供则自动下载")
    parser.add_argument("--signer-jar", help="uber-apk-signer.jar 路径，若未提供则自动下载")
    parser.add_argument("--java-bin", default="java", help="Java 执行路径 (默认 java)")
    args = parser.parse_args()

    base_apk = os.path.abspath(args.base_apk)
    module_apk = os.path.abspath(args.module_apk)
    output_apk = os.path.abspath(args.output_apk)

    if not os.path.exists(base_apk):
        print(f"[-] 错误: 官方底包未找到: {base_apk}")
        sys.exit(1)
    if not os.path.exists(module_apk):
        print(f"[-] 错误: 模块 APK 未找到: {module_apk}")
        sys.exit(1)

    tools_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "bin")
    os.makedirs(tools_dir, exist_ok=True)

    lspatch_jar = args.lspatch_jar or os.path.join(tools_dir, "lspatch.jar")
    signer_jar = args.signer_jar or os.path.join(tools_dir, "uber-apk-signer.jar")

    ensure_tool(lspatch_jar, LSPATCH_URL, "LSPatch")
    ensure_tool(signer_jar, UBER_SIGNER_URL, "Uber-APK-Signer")

    out_dir = os.path.dirname(output_apk)
    os.makedirs(out_dir, exist_ok=True)

    print(f"[*] 开始使用 LSPatch 注入模块 ...")
    print(f"    - 底包: {base_apk}")
    print(f"    - 模块: {module_apk}")
    print(f"    - 输出: {output_apk}")

    # 运行 LSPatch
    cmd_lspatch = [
        args.java_bin,
        "-jar",
        lspatch_jar,
        base_apk,
        "-m",
        module_apk,
        "-o",
        out_dir,
        "-f",
    ]
    res_patch = subprocess.run(cmd_lspatch, capture_output=True, text=True)
    if res_patch.returncode != 0:
        print(f"[-] LSPatch 执行失败 (退出码 {res_patch.returncode}):\n{res_patch.stderr}\n{res_patch.stdout}")
        sys.exit(1)

    # 查找 LSPatch 生成的产物
    base_prefix = os.path.splitext(os.path.basename(base_apk))[0]
    candidates = [
        os.path.join(out_dir, f)
        for f in os.listdir(out_dir)
        if f.startswith(base_prefix) and f.endswith("-lspatched.apk")
    ]
    if not candidates:
        candidates = [
            os.path.join(out_dir, f)
            for f in os.listdir(out_dir)
            if f.endswith("-lspatched.apk")
        ]

    if not candidates:
        print("[-] 错误: 未能在输出目录中找到 LSPatch 生成的 *-lspatched.apk")
        sys.exit(1)

    patched_raw = sorted(candidates, key=os.path.getmtime, reverse=True)[0]
    print(f"[*] LSPatch 产物生成成功: {patched_raw}")

    print("[*] 正在进行 APK 重签名与对齐 (uber-apk-signer) ...")
    cmd_sign = [
        args.java_bin,
        "-jar",
        signer_jar,
        "-a",
        patched_raw,
        "--allowResign",
        "--overwrite",
    ]
    res_sign = subprocess.run(cmd_sign, capture_output=True, text=True)
    if res_sign.returncode != 0:
        print(f"[-] 签名失败:\n{res_sign.stderr}\n{res_sign.stdout}")
        sys.exit(1)

    signed_candidate = patched_raw.replace(".apk", "-aligned-debugSigned.apk")
    final_source = signed_candidate if os.path.exists(signed_candidate) else patched_raw

    if os.path.exists(output_apk):
        os.remove(output_apk)
    shutil.move(final_source, output_apk)

    # 清理临时中间包
    if os.path.exists(patched_raw) and os.path.abspath(patched_raw) != os.path.abspath(output_apk):
        try:
            os.remove(patched_raw)
        except OSError:
            pass

    print(f"[+] 修补与签名完成! 产物保存至: {output_apk}")
    print(f"    产物大小: {os.path.getsize(output_apk)} 字节")
    set_github_output("patched_apk", output_apk)
    set_github_output("patched_apk_name", os.path.basename(output_apk))

if __name__ == "__main__":
    main()
