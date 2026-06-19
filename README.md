# 跑得快 Android

一个 Android 原生单机三人跑得快游戏，为家人使用场景设计，也作为 `pao-de-kuai` C++ 桌面版的 Kotlin Android 迁移版本维护。

仓库地址：https://github.com/sdcb/pdk-android

## 截图

![](https://github.com/user-attachments/assets/973b5591-48bb-4d63-8cca-ef1ee5a53bd3)

## 技术栈

- Kotlin / Android
- Jetpack Compose / Compose Canvas
- SoundPool 音效播放与独立调度
- Android VectorDrawable / adaptive icon
- JVM 单元测试
- Gradle Wrapper

## 项目结构

```text
.
├── app/                     # Android App、Compose UI、Canvas 游戏桌面、音频和资源
├── core/                    # 纯 Kotlin/JVM 规则、牌型、计分、AI 和协议测试
├── tools/                   # 本地调试和截图辅助脚本
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew
└── gradlew.bat
```

## 构建

项目使用 Gradle Wrapper，不需要全局安装 Gradle。当前配置使用 `compileSdk 37`、`targetSdk 35`、`minSdk 24`。

```powershell
.\gradlew.bat :core:test :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

常用产物：

- `app/build/outputs/apk/debug/app-debug.apk`：debug 包。
- `app/build/outputs/apk/release/app-release.apk`：开启 R8 和资源压缩的 release 包。

## 运行数据

- 设置保存到 Android app 私有目录的 `files/appsettings.json`。
- 每日对局统计保存到 `files/stat/yyyyMMdd.json`。
- 游戏规则、计分和基础 AI 位于 `core` 模块，可不依赖 Android 运行单元测试。

## License

MIT License. See [LICENSE](LICENSE).
