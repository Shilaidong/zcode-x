#!/usr/bin/env node
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { copyFile, mkdir, readFile, stat } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const androidDir = resolve(root, "packages/android");
const outDir = resolve(root, "dist/android");

async function sha256File(file) {
  const hash = createHash("sha256");
  hash.update(await readFile(file));
  return hash.digest("hex");
}

function run(command, args, options = {}) {
  console.log(`[android-build] ${command} ${args.join(" ")}`);
  const result = spawnSync(command, args, {
    cwd: options.cwd || root,
    stdio: "inherit",
    env: {
      ...process.env,
      ANDROID_HOME: process.env.ANDROID_HOME || "/opt/homebrew/share/android-commandlinetools",
      JAVA_HOME: process.env.JAVA_HOME || "/opt/homebrew/opt/openjdk@21",
      ...(options.env || {}),
    },
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`Command failed with status ${result.status}`);
  }
}

async function main() {
  const rootPackageJson = JSON.parse(await readFile(resolve(root, "package.json"), "utf8"));
  const version = rootPackageJson.version;
  console.log(`[android-build] Building Zcode-x Android APK v${version}...`);

  // 1. 检查是否存在分发包，若存在可选择内嵌
  const runtimeTarball = resolve(root, `dist/zcode/releases/${version}/zcode-${version}.tar.gz`);
  const assetsDir = resolve(androidDir, "app/src/main/assets");
  await mkdir(assetsDir, { recursive: true });

  const embedRuntime = process.argv.includes("--embed-runtime");
  if (embedRuntime) {
    const tarballStat = await stat(runtimeTarball).catch(() => null);
    if (tarballStat?.isFile()) {
      console.log(`[android-build] Embedding runtime tarball into APK assets: ${runtimeTarball}`);
      await copyFile(runtimeTarball, resolve(assetsDir, "zcode-runtime.tar.gz"));
    } else {
      console.warn("[android-build] Runtime tarball not found, skipping embedding.");
    }
  }

  // 2. 运行 Gradle 构建 Release APK
  console.log("[android-build] Running Gradle assembleRelease...");
  run("gradle", ["assembleRelease"], { cwd: androidDir });

  // 3. 收集产物
  const apkSource = resolve(androidDir, "app/build/outputs/apk/release/app-release.apk");
  await mkdir(outDir, { recursive: true });
  const apkTarget = resolve(outDir, `Zcode-x-${version}.apk`);
  await copyFile(apkSource, apkTarget);

  const hash = await sha256File(apkTarget);
  console.log("");
  console.log("=================================================");
  console.log(`✅ Zcode-x Android APK 构建成功！`);
  console.log(`📦 产物路径: ${apkTarget}`);
  console.log(`🔑 SHA-256:  ${hash}`);
  console.log("=================================================");
}

await main().catch((err) => {
  console.error("[android-build] Build failed:", err);
  process.exit(1);
});
