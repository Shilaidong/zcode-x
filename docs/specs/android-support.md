# Android Support Spec

## 产品规则

- ZCode-x Android 版本通过 Termux + proot-distro 提供完整本地 Agent 运行时
- 一键安装脚本 `scripts/android-install.sh` 自动配置环境
- Web UI 通过 `http://localhost:3030` 在浏览器/PWA 中使用
- Agent 功能完整可用；Terminal Tab 在 node-pty 不可用时优雅降级
- 安装产物 `zcode-x-server-linux-arm64.tar.gz` 通过 GitHub Release 分发

## 状态所有者

| 状态 | 所有者 | 备注 |
|------|--------|------|
| 安装/启动生命周期 | `scripts/android-install.sh` | shell 脚本管理 |
| HTTP 服务与 RPC | `@zcode/server-cli` | 已有的 `stageCli.ts` 管道 |
| Agent 运行时 | `apps/zcode-cli` (zcode.cjs) | 平台无关 |
| Web UI | `packages/web` + `packages/server/src/http.ts` | 静态资源托管 |
| 数据目录 | `ZCODE_DATA_BASE_DIR` env | 指向 proot 内 `~/.zcode-x` |

## 接口

- 构建命令：`pnpm --filter @zcode/server-cli stage -- --target linux-arm64`
- 启动环境变量：`ZCODE_DATA_BASE_DIR`, `ZCODE_PRODUCT_FLAVOR`, `ZCODE_WEB_STATIC_ROOT`
- Server HTTP API：`GET /api/server-info`, `GET /ws` (WebSocket RPC)
- 静态资源路径：`ZCODE_WEB_STATIC_ROOT` 指向 `web-dist/`

## 验收场景

1. 在 Termux proot-distro (Ubuntu ARM64) 中执行安装脚本，无人工干预完成
2. `zcode-x` 命令启动后，Chrome 打开 `http://localhost:3030` 看到完整 UI
3. Agent 对话、代码修改、命令执行全部正常
4. node-pty 缺失时 Terminal Tab 显示提示信息而非崩溃
5. 手机锁屏后服务保持运行（Foreground Service + WakeLock）
