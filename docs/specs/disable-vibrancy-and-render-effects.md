# Spec: 移除窗口透明/Vibrancy与重度渲染特效（Intel Mac 与通用性能极致优化）

## 背景与问题

在搭载 Intel 核显的 Mac 设备上，除思考组件特效外，还有四大系统级与渲染级开销导致 GPU 持续高负载与发热：

1. **macOS 窗口原生半透明与毛玻璃 (Vibrancy)**：
   - 主进程窗口开启了 `vibrancy: "under-window"`，底层色设为 `#00000000`。
   - `styles.css` 中将 `html, body, #root` 设为 `background: transparent !important`。
   - `DesktopWindowFrame.tsx` 在 macOS 下使用 60% 半透明混色 `bg-background-alt`。
   - 这迫使 macOS WindowServer 每一帧都要抓取窗口下方的桌面与后台像素，在 Intel 核显上跑多通道模糊着色器，并做全窗口 Alpha 合成。
2. **UI 内部 `backdrop-filter: blur(...)` 离屏模糊**：
   - 弹窗、通知（Toast）、吸顶头部、代码卡片等使用 `backdrop-blur-*`。
   - Chromium 必须拷贝显存缓冲区进行 GPU 卷积模糊计算，在统一内存/核显架构上严重打断管线（Pipeline Stall）。
3. **残余的流光循环动画**：
   - 工具执行（`ToolLayout`）、会话处理（`ConversationRowView`）等处仍在调用 `.animated-gradient-text`，其关键帧动画 `gradient-flow 4s linear infinite` 持续以 60 FPS 强刷 `background-clip: text`。
4. **流式文本的独立 GPU 图层激增**：
   - `[data-zcode-stream-animate="true"]` 设置了 `will-change: opacity` 和 900ms 缓动，强制 Chromium 为每一段吐出的文本单独分配 GPU 合成图层（RenderLayer），导致长文本输出时合成器开销剧增。

## 目标与修改方案

1. **实体不透明窗口（Opaque Window）**：
   - macOS 移除 `vibrancy: "under-window"` 与 `visualEffectState: "active"`。
   - 将主窗口底层背景色改为实体背景；`DesktopWindowFrame.tsx` 统一采用实体纯色底色 `bg-background`（或与系统主题同步的实体底色）。
   - `styles.css` 移除强制透明根背景，改用语义化主题实体背景。
   - 效果：Chromium 直接使用不透明硬件合成图层（Opaque CALayer），macOS 启用快速直通与遮挡剔除，静止时 GPU 占用降为 0%。
2. **消除 `backdrop-filter` 毛玻璃着色**：
   - 在全局样式中禁用 `backdrop-filter` 模糊滤镜（`backdrop-filter: none !important; -webkit-backdrop-filter: none !important;`），用轻量实色/半实色背景替代模糊。
   - 效果：杜绝离屏贴图拷贝与卷积着色，大幅改善滚动和弹窗交互时的帧率。
3. **关闭残余无限循环流光动画**：
   - 将 `.animated-gradient-text` 和 `.cua-group-gradient-text` 的循环关键帧动画关闭，改为静态文本展示。
4. **移除流式文本的 `will-change: opacity` 与 900ms 缓动**：
   - 取消流式段落创建独立合成图层，让字符瞬时直出。

## 状态所有者与兼容性
- 纯渲染层与外壳配置修改，不改动任何业务状态流、RPC 或通信协议。
- 保留暗色/明色主题无缝切换能力。

## 验收场景
1. macOS 下窗口不再透出底层桌面壁纸，呈现稳定干净的实体背景。
2. 工具执行、任务处理中不再有闪烁流光，文本正常可读。
3. 弹窗打开、列表滚动时不触发显卡高斯模糊滤镜。
4. 流式出字瞬时呈现，不产生复合图层抖动。
5. `pnpm typecheck`、`pnpm lint`、`pnpm architecture:check --changed` 全绿。
