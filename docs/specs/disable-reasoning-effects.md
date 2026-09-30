# Spec: 移除思考状态的滚动与闪烁动画特效及 Zcode-x 独立构建

## 背景与问题

在搭载 Intel 核心显卡（Intel UHD / Iris Graphics）的 macOS 设备上，运行 Electron 桌面端时，思考状态相关的两处动效会造成 GPU 重绘风暴：
1. **文案流光闪烁特效**：在未展开的流式输出阶段，“正在思考”（Thinking）文案使用了 `.animated-gradient-text`，依赖持续关键帧动画 `gradient-flow` 以及 `-webkit-background-clip: text` 配合透明文字颜色，引起 Chromium 每一帧持续重栅格化。
2. **单行思考内容滚动特效**：右侧流式摘要使用了 `QueuedSummaryContent`（基于 `motion/react` 的 `<AnimatePresence>` 与纵向位移/透明度过渡动画）以及 `maskImage: linear-gradient(...)` 左右边缘渐变遮罩，且每次文本更新都在驱动 `ResizeObserver` 与 `scrollLeft` 变更。

在没有专用高性能 GPU / 统一内存加速的 Intel Mac 上，上述复合动画与遮罩会导致 Chromium 的 GPU 渲染进程（`Google Chrome Helper (GPU)`）占用率居高不下（经常达到 100%~200%），引发严重发热、风扇狂转、掉帧乃至界面撕裂的渲染 Bug。

此外，用户需要一个单独命名的修改版构建 `Zcode-x`，可与官方正式版 `ZCode` 并存安装与同时运行，数据和配置互不冲突。

## 目标与产品规则

1. **直接出字、去除特效**：
   - “正在思考”文案采用静态常规文本展示（`text-foreground-subtle font-medium`），不再使用扫光/闪烁的 `.animated-gradient-text` 动画类。
   - 思考过程中的实时流式摘要，直接作为单行文本展示（配合标准单行省略截断 `truncate`），随大模型 token 输出实时流式更新字样，不再触发上下滚动的过渡动画（移除 `QueuedSummaryContent` 的 roll 动画与 `AnimatePresence`）。
   - 移除单行流式摘要容器上的渐变遮罩 `maskImage` / `WebkitMaskImage` 动态样式，消除额外的图层合成开销。
   - 移除持续驱动 `scrollLeft` 和 `ResizeObserver` 的滚动副作用，降低主线程与合成线程负担。
2. **独立命名与多版本共存（Zcode-x）**：
   - 产品命名设为 `Zcode-x`（打包产物为 `Zcode-x-3.14.3-mac-x64.dmg`，应用为 `Zcode-x.app`）。
   - 独立的 Bundle Identifier（`appId: dev.zcode.app.x`），使 macOS 系统层完全将其作为独立应用识别。
   - 独立的用户数据目录：`userData` 设为 `~/Library/Application Support/Zcode-x`，本地配置与数据目录隔离到 `~/.zcode-x`，避免与官方原版争抢 SQLite 锁（`tasks-index.sqlite`）与配置覆盖。
   - 独立的 Electron 单实例锁（基于独立的 userData），允许与官方版同时打开并独立工作。
   - 禁用针对官方版的后台静默自动更新，防止被官方原版安装包覆盖。
3. **保留原有布局与语义**：
   - 保留 Brain 图标、中点分隔符以及折叠触发箭头；
   - 保留 `data-reasoning-streaming-line="true"` 与 `data-reasoning-streaming-text="true"` 测试与无障碍语义标签；
   - 折叠/展开行为与展开后的思考详情文本排版维持不变。

## 状态所有者与数据流

- **状态所有者**：`ReasoningTrigger` / `Reasoning` 组件通过 `useReasoning()` 消费 `isStreaming`、`isOpen` 等流式状态，直接读取由父组件传入的 `streamingText`。
- **数据流**：
  - `streamingText` 随着 turn 流式推进 -> 提取最后非空行（`resolveReasoningStreamingSummary`） -> 直接渲染为单行文本节点 -> 纯 CSS 文本排版展示。

## 验收场景

1. **思考中（收起态）**：
   - “正在思考”文案呈纯色静态显示，无闪烁流动特效。
   - 右侧单行文本随流式吐字即时更新，不出现纵向滚动/弹跳切换动画。
   - 超长文本通过纯 CSS `truncate` 显示省略号，不加载复杂渐变遮罩。
2. **思考完成（收起态）**：
   - 显示“思考 · 持续了 X 秒”，与原有视觉保持一致。
3. **共存与独立性**：
   - 应用名展示为 `Zcode-x`，生成的 dmg 文件为 `Zcode-x-<version>-mac-x64.dmg`。
   - 安装后可与已有的官方 `ZCode` 并列在“应用程序”目录中，同时启动互不排斥。
   - 登录状态和会话数据保存在 `Zcode-x` 独立目录下，不污染官方版数据。
