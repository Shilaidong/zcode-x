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
    // Windows 下 gradle/tar 等是 .bat/.cmd shim，必须经 shell 解析 PATHEXT 才能找到。
    shell: process.platform === "win32",
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

  // 1. 确保 ARM64 JNI 原生库就绪 (libnode.so 及依赖动态链接库)
  const jniDir = resolve(androidDir, "app/src/main/jniLibs/arm64-v8a");
  const libnodeFile = resolve(jniDir, "libnode.so");
  const libnodeStat = await stat(libnodeFile).catch(() => null);
  if (!libnodeStat?.isFile()) {
    console.log("[android-build] JNI libraries not found, downloading prebuilt ARM64 binaries...");
    const jniLibsTarball = resolve(androidDir, "app/src/main/jniLibs/arm64-libs.tar.gz");
    await mkdir(resolve(androidDir, "app/src/main/jniLibs"), { recursive: true });
    const jniUrl = `https://github.com/Shilaidong/zcode-x/releases/download/v${version}-x/zcode-android-jnilibs-arm64.tar.gz`;
    const res = await fetch(jniUrl);
    if (!res.ok) throw new Error(`Failed to fetch ${jniUrl}: ${res.statusText}`);
    const { writeFile, rm } = await import("node:fs/promises");
    await writeFile(jniLibsTarball, Buffer.from(await res.arrayBuffer()));
    run("tar", ["-xzf", jniLibsTarball, "-C", resolve(androidDir, "app/src/main/jniLibs")]);
    await rm(jniLibsTarball, { force: true });
    console.log("[android-build] ARM64 JNI libraries downloaded and extracted.");
  }

  // 2. 检查是否存在分发包，若存在可选择内嵌
  const runtimeTarball = resolve(root, `dist/zcode/releases/${version}/zcode-${version}.tar.gz`);
  const assetsDir = resolve(androidDir, "app/src/main/assets");
  await mkdir(assetsDir, { recursive: true });

  const embedRuntime = process.argv.includes("--embed-runtime");
  const embeddedAsset = resolve(assetsDir, "zcode-runtime.tar.gz");
  if (embedRuntime) {
    const tarballStat = await stat(runtimeTarball).catch(() => null);
    if (tarballStat?.isFile()) {
      console.log(`[android-build] Embedding runtime tarball into APK assets: ${runtimeTarball}`);
      await copyFile(runtimeTarball, embeddedAsset);
    } else {
      console.warn("[android-build] Runtime tarball not found, skipping embedding.");
    }
  } else {
    const { rm } = await import("node:fs/promises");
    await rm(embeddedAsset, { force: true });
  }

  // 2. 运行 Gradle 构建 Release APK
  console.log("[android-build] Running Gradle assembleRelease...");
  run("gradle", ["assembleRelease"], { cwd: androidDir });

  // 3. 收集产物
  const apkSource = resolve(androidDir, "app/build/outputs/apk/release/app-release.apk");
  await mkdir(outDir, { recursive: true });
  const outputFileName = embedRuntime ? `Zcode-x-${version}-full.apk` : `Zcode-x-${version}.apk`;
  const apkTarget = resolve(outDir, outputFileName);
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
