<div align="center">

# WeType Enhance (18 键双拼增强版)

**微信输入法增强模块 · 18键双拼、下滑手势、界面美化、剪贴板一次补齐**

在运行时为微信输入法叠加自定义能力：全新 18 键双拼布局、全功能按键下滑手势、界面外观精细定制与总开关旁路、剪贴板持久化增强；同时针对 LSPosed / LSPatch 提供全环境深度兼容支持。

<p align="center">
  <a href="https://github.com/Amadeusssssss/WeType-Enhance"><img src="https://img.shields.io/badge/Repo-Amadeusssssss%2FWeType--Enhance-FB7299?style=flat-square" alt="Repository"></a>
  <img src="https://img.shields.io/badge/Android-12%2B-3DDC84?style=flat-square&amp;logo=android&amp;logoColor=white" alt="Android 12+">
  <img src="https://img.shields.io/badge/LSPosed-Supported-5C6BC0?style=flat-square" alt="LSPosed Supported">
  <img src="https://img.shields.io/badge/Stack-Jetpack%20Compose-4285F4?style=flat-square" alt="Jetpack Compose">
  <img src="https://img.shields.io/badge/License-AGPL--3.0-orange?style=flat-square" alt="AGPL-3.0">
</p>

<p align="center">
  <a href="#功能特性">功能特性</a> ·
  <a href="#安装与使用">安装与使用</a> ·
  <a href="#兼容性保障">兼容性保障</a> ·
  <a href="#常见问题">常见问题</a> ·
  <a href="#从源码构建">从源码构建</a> ·
  <a href="#上游与致谢">上游与致谢</a>
</p>
</div>

---

## 功能特性

设置页按「界面美化 / 按键手势 / 功能增强」三组组织。所有配置即时写入，支持广播动态同步并重启微信输入法进程后生效；模块只在运行时动态修改，不替换、不改动输入法安装包。

| 分组 | 包含能力 |
| :--- | :--- |
| **界面美化** | **新增美化总开关**（一键干净旁路还原官方底色）；背景 / 按键 / 候选词颜色、透明度、圆角、模糊与边缘高光 |
| **按键手势** | **新增 18 键双拼专属手势通道**；全键盘、九宫格、18 键下滑手势；25 种动作绑定、触觉反馈、大拇指快速甩动防抖与画布角标绘制 |
| **功能增强** | **新增 18 键双拼键盘布局动态注入**（7-6-5 黄金排列）；Logo / 字体替换、剪贴板增强、热更新防护 |
| **MIUI 附加** | 三方输入法全面屏优化解锁、小米短语校验解锁、剪贴板列表修复 |

---

### 🌟 新增重点功能

#### 1. 18 键双拼键盘布局动态注入
- 在「功能增强」中开启「18 键双拼键盘布局」；
- 模块在微信输入法请求双拼九宫格布局资源（`S8DoublePinT9Keyboard.json`）时，动态注入 7-6-5 黄金键位排列定义；
- 输入法底包零修改，彻底摆脱传统重打包改 smali 方案带来的签名与更新困扰。

#### 2. 18 键双拼按键手势与可视化编辑器
- 在「按键手势」中提供专门的 **18 键可视化按键映射编辑器**（包含 18 个主按键与空格键）；
- 支持为每一个双字母键位配置独立的下滑动作（支持全选、剪切、复制、粘贴、展开剪贴板等 25 种动作）；
- **默认预设**：`Z` (全选)、`XC` (剪切)、`V` (粘贴)、`BN` (复制)、`Space` (展开剪贴板)；
- **手势算法调优**：
  - 滑动触发阈值调优至自然的 `14dp`；
  - 放宽水平偏转容差至 56°（完美匹配单手大拇指下划对角弧度）；
  - 新增 **快速短甩 (Flick Detection)** 检测：抬手瞬间若判定为垂直短促下划即可触发手势并拦截原生打字。
- **动态画布角标**：输入法绘制按键时，实时在键位角标上清晰绘制手势动作名称（如“全选”、“粘贴”）。

#### 3. 界面美化总开关
- 在「界面美化」Tab 顶部提供全局「启用界面美化」开关；
- **开启时**：自由享受高斯模糊、透明背景、自定义配色与按键圆角定制；
- **关闭时**：核心 Hook 逻辑一键干净旁路，不干涉输入法任何原生绘制与 Window 属性，完美恢复微信输入法原厂官方底色与样式。

---

### 🎨 界面美化细项
- 浅色与深色模式各自保存一套配色，编辑时可实时查看渲染效果
- 窗口背景颜色与不透明度自由调整
- 按键颜色按映射分组批量替换，再单独微调透明度与圆角
- 背景模糊强度、平滑圆角与边缘高光（支持关闭与强度调节）
- 全局品牌强调色
- 工具栏图标背景不透明度
- 候选词背景透明度与圆角
- 首个候选词左边距、候选栏拼音左边距

### 📋 剪贴板与系统功能增强
- **键盘 Logo**：启用或关闭替换、单独控制显示隐藏；主体颜色支持跟随品牌色、跟随系统自适应黑白、自定义 `#RRGGBB`
- **字体替换**：可在微信官方字体、模块内置 WE-Regular 优化字体、系统默认字体之间切换
- **剪贴板**：多端同步条目可见化并持久保存；保留条数上限提升至 100,000 条、留存时长永久；单条文本上限提升至 1 亿字符；剪贴板页面内直接搜索，支持中文分词与拼音 / 首字母命中
- **剪贴板备份与恢复**：把剪贴板历史导出为 zip 保存到设备文件夹，或备份到 WebDAV；支持合并导入还原
- **系统防护**：阻止微信输入法热更新，降低依赖的 Hook 因云端热修复失效的概率

---

## 兼容性保障与底层架构

针对不同用户的运行环境进行了深度适配与兜底强化：

1. **DexKitLoader 三级原生库自愈加载**：
   - 解决标准 **LSPosed 框架** 下由于 ClassLoader 命名空间隔离及输入法宿主私有目录无 so 导致的 `UnsatisfiedLinkError`；
   - 自动检测并从模块 APK 中提取架构匹配的 `libdexkit.so` 到宿主私有 `codeCacheDir` / `cacheDir`，并通过绝对路径安全加载，保证手势在各类 Root / LSPosed / Zygisk / KernelSU 下 100% 挂载成功。
2. **动态特征多态分发 Hook**：
   - 摒弃对特定版本混淆类名/方法名的硬编码；
   - 基于 `(selfdraw.*, MotionEvent, selfdraw.*)Z` 特征签名同时挂载 QWERTY、18 键/九宫格以及空格键专用分发通道。
3. **跨进程配置 SharedPreferences 广播同步通道**：
   - 针对 LSPosed 环境下跨 UID 存储读取受阻的问题，增加 `ACTION_SYNC_HOST_SETTINGS` 显式广播同步通道，桌面 App 修改保存后输入法内部立即更新持久化。

---

## 安装与使用

### 使用要求
- Android 12 及以上
- 已安装微信输入法（官方原版或 LSPatch 免 Root 版）
- LSPosed、LSPatch 或兼容的 Xposed 框架

### 安装步骤
1. **已 Root / LSPosed 用户**：
   - 安装独立模块 APK；
   - 在 LSPosed 管理器中启用模块，作用域勾选 **微信输入法**；
   - 重启微信输入法进程后生效。
2. **免 Root / LSPatch 用户**：
   - 直接使用构建好的免 Root 修补版微信输入法 APK；
   - 安装后设置微信输入法为系统默认输入法，并在桌面打开「WeType 增强」应用进行手势或外观配置。

---

## 从源码构建

准备 JDK 17/21 与 Android SDK，然后执行：

```bash
# 编译模块 Release APK
./gradlew :app:assembleRelease
```

编译产物位于 `app/build/outputs/apk/release/`。

---

## 上游与致谢

- 本项目 Fork 自上游：[Costben/WeType-Enhance](https://github.com/Costben/WeType-Enhance)，感谢原作者在手势、剪贴板与美化上的优秀基础实现。
- 原作者上游：[NEORUAA/WeType_UI_Enhanced](https://github.com/NEORUAA/WeType_UI_Enhanced)，感谢打下界面美化与 MIUI 解锁的基础。
- [MIUI_IME_Unlock (MIT)](https://github.com/RC1844/MIUI_IME_Unlock)：MIUI 全面屏优化限制的解锁实现。
- [miuix](https://github.com/compose-miuix-ui/miuix)：设置页使用的 Compose UI 组件库。
- [@xu-qian123](https://github.com/xu-qian123)：提供键帽定位解析思路。

## 开源许可

本项目遵循 AGPL-3.0 协议开源。
