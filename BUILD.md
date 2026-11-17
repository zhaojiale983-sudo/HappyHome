# HappyHome Android 打包项目

此项目将原 HappyHome 网页原样放入 Android WebView，不重写网页功能。

## 编译 APK

### 方式一：GitHub Actions
1. 将本项目中的全部文件上传到你的 `HappyHome` 仓库根目录（允许覆盖同名的原网页文件；本项目已将原始网页文件放在 `app/src/main/assets/`）。
2. 确认仓库的 Actions 功能已启用。
3. 打开仓库的 **Actions → Build HappyHome APK → Run workflow**。
4. 等待任务完成，在页面底部的 Artifacts 下载 `HappyHome-debug-apk`，解压即可得到 `app-debug.apk`。

### 方式二：Android Studio
用 Android Studio 打开本目录，等待 Gradle 同步，然后运行 `app` 或执行 `gradle assembleDebug`。

## 当前适配
- 应用名称 HappyHome。
- 使用网页内置的 APP_ICON 图片作为 Android 启动图标。
- 原网页文件保留在 `app/src/main/assets/`。
- WebView 开启 JavaScript、DOM Storage、本地文件读取与网络访问。
- 支持系统文件选择器、网页下载、安卓返回键。
- 状态栏和导航栏采用淡奶黄色。

## 网络依赖说明
原网页仍引用 jsDelivr/cdnjs 之外的 CDN（当前为 cdnjs 的 JSZip、Mammoth）及在线图标/网站/API 服务。应用本体和主网页资源内置，但这些在线服务仍需网络；本包没有擅自替换或删除原网页依赖。

## 重要
当前提供的是 Android Studio/Actions 可编译项目，不是已经编译验证的 APK。需要在 GitHub Actions 或 Android Studio 中完成构建后才会产生 APK。
