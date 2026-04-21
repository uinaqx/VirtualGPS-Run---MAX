# 虚拟跑步 (VirtualRun)

一款免 Root 的 Android 虚拟跑步定位 App，支持在地图上规划路线并模拟真实跑步。

## 功能特性

- **免 Root**：使用 Android 标准 Mock Location API，无需 Root 权限
- **地图选点**：在 Google Maps 上点击选择起点、终点和途经点
- **配速调节**：可调节跑步配速（3-15 分钟/公里）
- **速度扰动**：自动添加 ±15% 随机波动，模拟真实跑步的自然速度变化
- **实时进度**：显示路线距离、预计用时和跑步进度

## 截图

![截图1](screenshots/screenshot1.png)
*地图选点和路线规划*

## 使用方法

### 1. 安装与设置

1. 下载最新版本的 APK（在 [Releases](../../releases) 页面）
2. 安装 APK 到 Android 手机
3. 打开应用，授予位置权限和通知权限
4. 进入 **设置 → 系统 → 开发者选项 → 模拟位置信息应用**，选择"虚拟跑步"

### 2. 开始虚拟跑步

1. 在地图上点击设置起点
2. 继续点击添加途经点
3. 点击设置终点，形成完整路线
4. 拖动滑块设置配速
5. 点击"开始"按钮启动虚拟定位

## 构建项目

### 环境要求

- JDK 17+
- Android SDK (API 34)
- Android Studio Arctic Fox 或更高版本

### 配置 Google Maps API Key

1. 访问 [Google Cloud Console](https://console.cloud.google.com/)
2. 创建新项目，启用 **Maps SDK for Android**
3. 创建 API Key
4. 编辑 `app/src/main/AndroidManifest.xml`，替换 `YOUR_API_KEY_HERE`

### 构建命令

```bash
./gradlew assembleDebug
```

APK 将生成在 `app/build/outputs/apk/debug/app-debug.apk`

## 项目结构

```
app/src/main/java/com/virtualrun/app/
├── MainActivity.kt              # 主 Activity
├── model/
│   └── RoutePoint.kt            # 数据模型（路线点、路线、运动状态）
├── service/
│   └── MockLocationService.kt   # Mock Location 服务（核心功能）
├── algorithm/
│   └── TrajectoryInterpolator.kt # 轨迹插值与速度扰动算法
└── ui/
    └── MainScreen.kt            # Compose UI 界面
```

## 技术栈

- **语言**：Kotlin
- **UI 框架**：Jetpack Compose + Material 3
- **地图**：Google Maps Android SDK + Maps Compose
- **架构**：MVVM
- **异步**：Kotlin Coroutines + Flow
- **最低版本**：Android 8.0 (API 26)

## 核心技术实现

### Mock Location Service
- 检查并引导用户启用模拟位置权限
- 注册 Android TestProvider
- 以 1Hz 频率发送虚拟位置数据

### 轨迹插值算法
- 线性插值计算路线上任意位置
- 速度扰动：±15% 随机浮动 + 正弦波自然变化
- 避免绝对匀速，模拟真实跑步体验

## 注意事项

1. **模拟位置权限**：使用前必须在开发者选项中将本应用设置为模拟位置应用
2. **Google Maps API**：需要使用自己的 API Key 才能正常显示地图
3. **后台限制**：部分 Android 系统可能限制后台位置更新
4. **检测风险**：某些应用可能会检测到使用了 Mock Location

## 开源协议

[MIT License](LICENSE)

## 免责声明

本应用仅供学习和测试使用，请勿用于违反服务条款的用途。使用本应用产生的任何后果由用户自行承担。

## 贡献

欢迎提交 Issue 和 Pull Request！

## 更新日志

### v1.0.0 (2026-03-10)
- 初始版本发布
- 实现基础地图选点和路线规划
- 实现 Mock Location 核心功能
- 实现轨迹插值和速度扰动算法
