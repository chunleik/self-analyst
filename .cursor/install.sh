#!/usr/bin/env bash
# Cloud Agent 环境安装脚本：幂等地准备 SelfAnalyst 的构建与测试依赖。
# 基础镜像已提供 JDK 21、Node.js、pnpm 与 Rust/Cargo，这里补齐缺失的 Maven，
# 并预热 Maven 依赖缓存，使后续 `mvn test` 无需重复下载。
set -euo pipefail

MAVEN_VERSION="3.9.9"
MAVEN_HOME="/opt/apache-maven-${MAVEN_VERSION}"
MAVEN_TARBALL="apache-maven-${MAVEN_VERSION}-bin.tar.gz"
MAVEN_URL="https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/${MAVEN_TARBALL}"

if [ ! -x "${MAVEN_HOME}/bin/mvn" ]; then
    echo "[install] 安装 Apache Maven ${MAVEN_VERSION} ..."
    tmp_dir="$(mktemp -d)"
    trap 'rm -rf "${tmp_dir}"' EXIT
    curl -fsSL -o "${tmp_dir}/${MAVEN_TARBALL}" "${MAVEN_URL}"
    sudo tar -xzf "${tmp_dir}/${MAVEN_TARBALL}" -C /opt
    rm -rf "${tmp_dir}"
    trap - EXIT
else
    echo "[install] 已检测到 Apache Maven ${MAVEN_VERSION}，跳过下载"
fi

sudo ln -sf "${MAVEN_HOME}/bin/mvn" /usr/local/bin/mvn
hash -r

mvn -version

echo "[install] 预热 Maven 依赖并构建各模块（跳过测试）..."
mvn -B -DskipTests install

echo "[install] 完成"
