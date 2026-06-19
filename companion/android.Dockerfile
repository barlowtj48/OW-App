# Android build image for the OneWheel Pebble companion (Capacitor) app.
# Keeps Node, npm, Java, and the Android SDK tooling inside Docker so the host
# only needs Docker + Task. Mirrors the CarpenterCalculator build image.
FROM node:22-trixie-slim

ENV DEBIAN_FRONTEND=noninteractive \
  ANDROID_HOME=/opt/android-sdk \
  ANDROID_SDK_ROOT=/opt/android-sdk \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  CAPACITOR_TELEMETRY_DISABLED=1
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

ARG ANDROID_COMMANDLINE_TOOLS_VERSION=11076708
ARG ANDROID_PLATFORM=android-35
ARG ANDROID_BUILD_TOOLS=35.0.0

RUN apt-get update \
  && apt-get upgrade -y \
  && apt-get install -y --no-install-recommends \
  ca-certificates \
  git \
  openjdk-21-jdk-headless \
  unzip \
  wget \
  && rm -rf /var/lib/apt/lists/*

RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" /root/.android \
  && touch /root/.android/repositories.cfg \
  && wget -q "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_COMMANDLINE_TOOLS_VERSION}_latest.zip" -O /tmp/android-commandline-tools.zip \
  && unzip -q /tmp/android-commandline-tools.zip -d "${ANDROID_HOME}/cmdline-tools" \
  && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" \
  && rm /tmp/android-commandline-tools.zip \
  && yes | sdkmanager --licenses >/dev/null \
  && sdkmanager \
  "platform-tools" \
  "platforms;${ANDROID_PLATFORM}" \
  "build-tools;${ANDROID_BUILD_TOOLS}"

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

CMD ["bash"]
