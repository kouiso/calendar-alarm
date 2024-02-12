FROM mcr.microsoft.com/vscode/devcontainers/base:0-focal

# ユーザー`vscode`の作成
RUN id -u vscode &>/dev/null || useradd -m vscode -s /bin/bash && \
    echo "vscode ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/vscode && \
    chmod 0440 /etc/sudoers.d/vscode

# 必要な依存関係のインストール
RUN apt-get update && apt-get install -y curl git autoconf bison build-essential \
    libssl-dev libyaml-dev libreadline6-dev zlib1g-dev libncurses5-dev \
    libffi-dev libgdbm6 libgdbm-dev libdb-dev

# asdfのインストール
RUN git clone https://github.com/asdf-vm/asdf.git /home/vscode/.asdf --branch v0.8.0
ENV PATH="/home/vscode/.asdf/bin:/home/vscode/.asdf/shims:$PATH"

# asdfプラグインの追加
RUN asdf plugin-add flutter && \
    asdf plugin-add ruby && \
    asdf plugin-add dart && \
    asdf plugin-add java

# プロジェクトディレクトリの作成
WORKDIR /workspace

# .tool-versionsファイルのコピー
COPY .tool-versions ./

# .tool-versionsからバージョンを読み込み、インストールする
# cocoapodsを除外
RUN grep -v '^cocoapods ' .tool-versions | while read line; do \
    tool=$(echo $line | awk '{print $1}'); \
    version=$(echo $line | awk '{print $2}'); \
    asdf install $tool $version; \
    done

# `DEVCONTAINER` 環境変数を設定
ENV DEVCONTAINER=true

# .sshディレクトリのパーミッション設定
USER root
RUN mkdir -p /home/vscode/.ssh && \
    chown -R vscode:vscode /home/vscode/.ssh && \
    chmod 700 /home/vscode/.ssh
USER vscode