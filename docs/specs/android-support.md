# Android Support Spec

## 1. 产品形态与路线

- **Phase 1（命令行/底层环境已完成）**：通过 Termux + proot-distro 提供完整的底层 Linux glibc 与 Node.js 运行时，通过 `scripts/android-install.sh` 一键安装。
- **Phase 2（独立 Android 客户端 APK，当前进行中）**：
  - 用户下载 `Zcode-x.apk`，安装后手机桌面上直接拥有独立应用图标。
  - 点击图标启动：自动静默拉起后台服务，管理 Node.js 与 Agent 运行时。
  - 单窗口原生体验：采用 Android WebView 全屏沉浸展示，无浏览器地址栏，自动导航至本地服务。
  - 后台保活：通过 Android Foreground Service（常驻通知）与 WakeLock 防止锁屏休眠被操作系统查杀。
  - 退出 App 时自动安全回收底层子进程与端口。

## 2. 状态所有者与架构模型

```text
┌────────────────────────────────────────────────────────┐
│                   Zcode-x.apk 原生应用                 │
│                                                        │
│  [MainActivity] (UI 呈现层)                             │
│     ├── Android WebView (Chromium 内核)                │
│     ├── Splash 启动引导状态机 (Starting -> Ready)       │
│     └── AndroidPlatformBridge (JS <-> Kotlin 原生交互) │
│                                                        │
│  [ServerService] (生命周期与进程所有者)                  │
│     ├── Foreground Service Notification (保活与状态显示)│
│     ├── RuntimeInstaller (首次运行释放或更新运行时)       │
│     └── NodeProcessRunner (拉起 zcode-server / agent)  │
└──────────────────────────┬─────────────────────────────┘
                           │ 127.0.0.1:3030 (HTTP + WS)
┌──────────────────────────▼─────────────────────────────┐
│                 ZCode 本地服务与 Agent 运行时             │
│  - @zcode/server (Hono HTTP + WebSocket RPC)           │
│  - @zcode/web (React 19 单页静态资源)                   │
│  - @zcode/cli (zcode.cjs Agent 核心执行器)              │
│  - ~/.zcode-x (私有数据空间，SQLite 索引，工作区缓存)     │
└────────────────────────────────────────────────────────┘
```

| 状态 | 唯一所有者 | 说明 |
|------|-----------|------|
| APK UI 窗口与导航 | `MainActivity.kt` | 管理 WebView 生命周期、回退键与沉浸式全屏 |
| 后台服务与进程拉起 | `ServerService.kt` | Android Foreground Service，单例控制底层 Node.js 进程 |
| 运行时资源释放 | `RuntimeManager.kt` | 校验版本并在必要时从 assets 释放运行时包 |
| 业务状态与 Agent 对话 | `@zcode/server` & `zcode.cjs` | 既有跨平台状态机，数据隔离在 `~/.zcode-x` |

## 3. 接口规范

- **通信协议**：WebView 通过 `http://127.0.0.1:3030` 访问前端静态资源，通过 `/ws` 建立 WebSocket RPC 会话。
- **原生桥接 (`AndroidBridge`)**：
  - `showNotification(title, message)`：调用 Android 系统的原生通知栏提醒
  - `getAppVersion()`：返回当前 APK 构建版本号
  - `restartServer()`：从前端触发后端进程重启
- **环境变量注入**：
  - `ZCODE_PRODUCT_FLAVOR="zcode-x"`
  - `ZCODE_DATA_BASE_DIR="/data/data/dev.zcode.app.x/files"`
  - `PORT="3030"`
  - `ZCODE_SERVER_HOST="127.0.0.1"`

## 4. 验收场景

1. **零命令启动**：安装 APK 后点击图标，应用显示启动引导页，1~2 秒内自动进入 ZCode 主工作区。
2. **离线与内置资源**：首次打开即使在弱网环境下，也能从安装包内释放基础静态资源进入界面。
3. **全功能执行**：在 App 内部可创建会话、与 Agent 交互、执行文件读写、调用 bash/shell 工具。
4. **后台稳定性**：切换至其他应用或手机熄屏 5 分钟后切回，会话保持连接，Agent 正在执行的任务不中断。
5. **干净退出**：用户在多任务卡片滑动杀死应用时，关联的 Node 子进程一并释放，不残留僵尸进程。

