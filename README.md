# 抖音红包助手 — Android 版

功能完全等同 `auto_swipe_v7.py`，直接在手机上运行，无需 ADB 连接电脑。

## 功能

- **实时屏幕截取**：MediaProjection 持续捕获屏幕帧
- **红包金环检测**：红色像素定位红包，金色像素统计判定环是否静止
- **人工模拟上滑**：曲线轨迹 + 变速节奏，非机械直线
- **完整性保护**：红包不完整 → 60 秒冷却，禁止上滑
- **防卡死兜底**：80 秒无上滑 → 强制上滑

## 构建 APK（三选一，无需本地 Android SDK）

### 方法一：GitHub Actions 在线编译（推荐，零安装）

只需把代码推到 GitHub，剩下的全自动。

**步骤：**
1. 在 GitHub 创建新仓库（比如 `douyin-swipe-app`）
2. 把本目录推上去：
```bash
cd douyin-swipe-app
git init
git add .
git commit -m "init"
git branch -M main
git remote add origin https://github.com/你的用户名/douyin-swipe-app.git
git push -u origin main
```
3. 推送后 GitHub Actions 自动开始编译
4. 进入仓库 → **Actions** 标签 → 点最新的 workflow run → **Artifacts** 下载 `douyin-swipe-debug.zip`
5. 解压得到 `app-debug.apk`，直接装到手机上

> 配置文件已在 `.github/workflows/build-apk.yml`，无需修改。

### 方法二：Google IDX 在线 IDE（浏览器内编译）

Google 的云端 IDE，内置 Android SDK，无需安装任何东西。

1. 打开 https://idx.dev
2. 用 Google 账号登录
3. Import repo → 输入 GitHub 仓库地址（或直接上传源码）
4. 选择 Android 模板
5. 在终端运行 `./gradlew assembleDebug`
6. 左侧文件列表找到 APK 下载

### 方法三：Android Studio（本地，功能最全）

1. 安装 [Android Studio](https://developer.android.com/studio)
2. File → Open → 选择本目录
3. Build → Build APK(s)
4. APK 位于 `app/build/outputs/apk/debug/`

## 安装与使用

### 1. 安装 APK

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 2. 首次配置

1. 打开「抖音红包助手」
2. 点击「开始」→ 按引导：
   - 授予**悬浮窗权限**
   - 到系统设置 → 无障碍 → 开启「抖音红包助手」
   - 授权**屏幕录制**（系统弹窗点「立即开始」）
3. 状态栏出现通知「正在监控红包金环...」即正常运行
4. 打开抖音刷视频即可

### 3. 停止

- 点击 App 内「停止」按钮
- 或在系统设置 → 无障碍 → 关闭服务

## 项目结构

```
app/src/main/java/com/douyin/swipe/
├── MainActivity.kt      # UI + 权限管理
├── SwipeService.kt      # 无障碍服务主控
├── RingDetector.kt      # 红包金环检测引擎
└── HumanSwipe.kt        # 人工模拟手势
```

## 检测参数（与 Python 版一致）

| 参数 | 值 | 说明 |
|------|-----|------|
| STATIC_FRAMES_NEEDED | 3 | 静止判定帧数 |
| RED_INTEGRITY_MIN | 0.50 | 红包完整性阈值 |
| ROI_TOLERANCE | 10px | 位置容差 |
| ENVELOPE_MIN_CY | 250 | 红包最小 y 坐标 |
| INCOMPLETE_COOLDOWN | 60s | 不完整冷却 |
| MAX_SWIPE_INTERVAL | 80s | 防卡死兜底 |
| gold stable diff | ≤20 | 金环静止判定 |
