#!/usr/bin/env python3
"""
微信输入法官方版本自动巡检工具
数据源：严格直连微信输入法官方站点 https://z.weixin.qq.com/
解析页面首屏注入的 window.injectData.appChangelog (platform: 2 代表 Android)
"""

import argparse
import json
import os
import re
import sys
import urllib.request
import zipfile

OFFICIAL_URL = "https://z.weixin.qq.com/"
DEFAULT_CONFIG_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "../../config/official_version.json"
)

def fetch_official_changelog():
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    }
    req = urllib.request.Request(OFFICIAL_URL, headers=headers)
    print(f"[*] 正在请求官方源: {OFFICIAL_URL} ...")
    with urllib.request.urlopen(req, timeout=15) as response:
        html = response.read().decode("utf-8", errors="replace")

    idx = html.find("window.injectData=")
    if idx == -1:
        raise ValueError("无法在官网 HTML 中找到 window.injectData 数据注入块")

    start = idx + len("window.injectData=")
    end_script = html.find("</script>", start)
    if end_script == -1:
        raise ValueError("未找到 script 结束标签")

    json_text = html[start:end_script].rstrip(";").strip()
    data = json.loads(json_text)
    changelogs = data.get("appChangelog", [])
    
    # platform: 2 为 Android 平台
    android_logs = [item for item in changelogs if item.get("platform") == 2]
    if not android_logs:
        raise ValueError("官网未检索到 platform=2 (Android) 的更新日志")

    android_logs.sort(key=lambda x: (x.get("release_date", 0), x.get("id", 0)), reverse=True)
    return android_logs[0]

def parse_version_tuple(ver_str):
    cleaned = re.sub(r"[^\d.]", "", ver_str.strip())
    parts = []
    for p in cleaned.split("."):
        if p.isdigit():
            parts.append(int(p))
    return tuple(parts)

def html_to_markdown(html_text):
    if not html_text:
        return ""
    text = re.sub(r"<br\s*/?>", "\n", html_text, flags=re.IGNORECASE)
    text = re.sub(r"</p>", "\n", text, flags=re.IGNORECASE)
    text = re.sub(r"</h[1-6]>", "\n", text, flags=re.IGNORECASE)
    text = re.sub(r"<h[1-6][^>]*>", "\n- ", text, flags=re.IGNORECASE)
    text = re.sub(r"&nbsp;", " ", text, flags=re.IGNORECASE)
    text = re.sub(r"<[^>]+>", "", text)
    lines = [line.strip() for line in text.splitlines()]
    result = []
    for line in lines:
        if not line:
            continue
        cleaned_line = re.sub(r"^-\s*[-*•]\s*", "- ", line)
        if not cleaned_line.startswith(("-", "*")):
            cleaned_line = "- " + cleaned_line
        result.append(cleaned_line)
    return "\n".join(result).strip()


def get_official_download_url():
    """
    优先通过腾讯官方直连落地页 (a.app.qq.com) 动态提取腾讯官方 CDN 全量 APK 直链
    全量 APK 为独立单体包，彻底避免 Google Play / APKPure 分包分片 (Split APKs / XAPK) 导致的 INSTALL_FAILED_MISSING_SPLIT
    """
    try:
        url = "https://a.app.qq.com/o/simple.jsp?pkgname=com.tencent.wetype"
        headers = {"User-Agent": "Mozilla/5.0 (Linux; Android 14; Pixel 8)"}
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=10) as resp:
            html = resp.read().decode("utf-8", errors="replace")
        idx = html.find("window.systemData")
        if idx != -1:
            start = html.find("{", idx)
            depth = 0
            end = start
            for i in range(start, len(html)):
                if html[i] == "{": depth += 1
                elif html[i] == "}":
                    depth -= 1
                    if depth == 0:
                        end = i + 1
                        break
            data = json.loads(html[start:end])
            apk_url = data.get("appDetail", {}).get("apkUrl")
            if apk_url and apk_url.startswith("http"):
                print(f"[+] 成功解析到腾讯官方 CDN 直连全量底包: {apk_url}")
                return apk_url
    except Exception as e:
        print(f"[-] 解析腾讯官方 CDN 直链异常: {e}")

    # 保底源
    print("[*] 降级采用 APKPure 镜像源获取底包...")
    return "https://d.apkpure.net/b/APK/com.tencent.wetype?version=latest"

def download_official_apk(target_apk_path):
    """
    下载官方全量单体 APK。
    """
    download_url = get_official_download_url()
    print(f"[*] 正在拉取官方底包: {download_url} ...")
    os.makedirs(os.path.dirname(os.path.abspath(target_apk_path)), exist_ok=True)
    temp_download = target_apk_path + ".tmp"

    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Accept": "*/*",
    }
    req = urllib.request.Request(download_url, headers=headers)
    
    with urllib.request.urlopen(req, timeout=60) as response:
        with open(temp_download, "wb") as f:
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                f.write(chunk)

    # 检查是否为 XAPK / ZIP
    if zipfile.is_zipfile(temp_download):
        with zipfile.ZipFile(temp_download, "r") as z:
            namelist = z.namelist()
            apk_candidates = [n for n in namelist if n.endswith(".apk")]
            target_apk_name = None
            if "com.tencent.wetype.apk" in namelist:
                target_apk_name = "com.tencent.wetype.apk"
            elif "base.apk" in namelist:
                target_apk_name = "base.apk"
            elif apk_candidates:
                target_apk_name = apk_candidates[0]

            if target_apk_name:
                print(f"[*] 从 XAPK 压缩包中解压基础 APK: {target_apk_name} ...")
                with open(target_apk_path, "wb") as out_f, z.open(target_apk_name) as in_f:
                    while True:
                        buf = in_f.read(1024 * 1024)
                        if not buf:
                            break
                        out_f.write(buf)
                os.remove(temp_download)
            else:
                os.rename(temp_download, target_apk_path)
    else:
        os.rename(temp_download, target_apk_path)

    print(f"[+] 官方底包下载完毕: {target_apk_path} (大小: {os.path.getsize(target_apk_path)} 字节)")

def set_github_output(key, value):
    github_output = os.getenv("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a", encoding="utf-8") as f:
            f.write(f"{key}={value}\n")
    print(f"[Output] {key}={value}")

def main():
    parser = argparse.ArgumentParser(description="微信输入法官方版本检查工具")
    parser.add_argument("--config", default=DEFAULT_CONFIG_PATH, help="版本配置文件路径")
    parser.add_argument("--force", action="store_true", help="强制标记为有更新")
    parser.add_argument("--download", help="若有更新，将官方 APK 下载到指定文件路径")
    parser.add_argument("--check-only", action="store_true", help="仅检测版本，不执行下载")
    parser.add_argument("--changelog-output", help="输出官方更新日志至 Markdown 文件")
    args = parser.parse_args()

    config = {}
    config_path = os.path.abspath(args.config)
    if os.path.exists(config_path):
        with open(config_path, "r", encoding="utf-8") as f:
            config = json.load(f)

    last_ver = config.get("last_checked_version", "0.0.0")
    base_ver = config.get("base_version", "3.5.4")
    print(f"[*] 当前本地配置记录版本: last_checked={last_ver}, base={base_ver}")

    latest_info = fetch_official_changelog()
    latest_ver = latest_info.get("version", "").strip()
    title = latest_info.get("title", "")
    release_date = latest_info.get("release_date", 0)
    cl_md = html_to_markdown(latest_info.get("content_html", ""))

    if args.changelog_output:
        os.makedirs(os.path.dirname(os.path.abspath(args.changelog_output)), exist_ok=True)
        with open(args.changelog_output, "w", encoding="utf-8") as f:
            f.write(cl_md + "\n")
        print(f"[+] 官方更新日志已导出至: {args.changelog_output}")

    print(f"[+] 官方源检测到最新发布版本: {latest_ver}")
    print(f"    发布标题: {title}")
    print(f"    发布日期戳: {release_date}")

    t_latest = parse_version_tuple(latest_ver)
    t_recorded = parse_version_tuple(last_ver)

    has_update = (t_latest > t_recorded) or args.force

    if has_update:
        print(f"[!] 发现新版本更新: {last_ver} -> {latest_ver} (force={args.force})")
        set_github_output("has_update", "true")
        set_github_output("official_version", latest_ver)
        set_github_output("release_title", title)

        if args.download and not args.check_only:
            download_official_apk(args.download)
            set_github_output("official_apk", args.download)
    else:
        print(f"[*] 官方当前无更新 (最新 {latest_ver} <= 本地记录 {last_ver})")
        set_github_output("has_update", "false")
        set_github_output("official_version", latest_ver)

if __name__ == "__main__":
    main()
