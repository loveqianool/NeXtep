# FreeWindow

Android 自由窗口模块（Xposed/LSPosed），基于 [NeXtep](https://github.com/lujinxin/NeXtep) 重写。

## 功能

- **常驻通知**：点击通知打开应用面板，查看/切换后台窗口
- **自由窗口**：从应用面板启动任意应用，以可拖动、可缩放的悬浮窗口运行
- **后台运行**：窗口可最小化到后台（park），应用继续运行，点击恢复
- **最大化**：窗口可一键最大化/恢复
- **单窗口静音**：每个窗口可独立静音（基于 AppOps `PLAY_AUDIO`）
- **输入法支持**：窗口内输入框可正常弹出键盘（LOCAL IME 策略）

## 要求

- Android 15+（`minSdk 35`）
- Root + LSPosed/Vector（JingMatrix 分支已测试）
- 作用域需勾选：**Android 系统**、**System Framework**、**SystemUI**

## 安装

1. 从 [Releases](../../releases) 下载最新 APK
2. 安装后在 LSPosed/Vector 中启用 **FreeWindow** 模块
3. 勾选上述三个作用域
4. **完整重启手机**（仅重启 SystemUI 不够，system_server 需重载模块）

## 使用

- 下拉通知栏，点击 **FreeWindow** 常驻通知打开应用面板
- 在应用面板搜索/选择应用，以自由窗口启动
- 标题栏按钮：▽ 最小化到后台、⛶ 最大化/恢复、🔊/🔇 静音、✕ 关闭
- 拖动标题栏移动窗口，拖动右下角隐形区域缩放（松手后生效）
- 后台窗口在通知栏和应用面板的"后台"区列出，点击切换，✕ 关闭

## 静音说明

- 静音基于 AppOps `PLAY_AUDIO` 按包名生效，**不是按窗口**：同一应用的多个窗口、以及主屏上的同一应用都会被静音
- 关闭最后一个静音窗口时会自动取消静音
- 如遇异常静音残留，重启后会自动清理

## 构建

```bash
./gradlew :app:assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`，CI 会自动构建并上传 Artifact。

## 已知问题

- Vector（JingMatrix）的 `VectorConfigCache` 在部分版本上解析 APK 失败（`PackageParser failed ... using fallback ApplicationInfo`），导致 system_server 加载旧版模块代码。如遇此问题，请向 Vector 反馈
- 输入法在部分应用上可能需要点两次输入框

## License

[LICENSE](LICENSE)
