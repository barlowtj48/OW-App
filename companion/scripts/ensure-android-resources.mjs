#!/usr/bin/env node
// Re-applies the OneWheel Pebble bridge customizations onto a freshly generated
// Capacitor android/ project. Capacitor regenerates android/ from scratch (it is
// git-ignored), so this script makes the native wiring reproducible. It is
// idempotent: running it multiple times yields the same result.
//
// Mirrors the CarpenterCalculator `ensure-android-resources.mjs` pattern.

import { readFileSync, writeFileSync, existsSync, mkdirSync, copyFileSync, rmSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');

const androidDir = join(root, 'android');
if (!existsSync(androidDir)) {
  console.error('android/ not found. Run `cap add android` first.');
  process.exit(1);
}

const KOTLIN_VERSION = '1.9.24';
const PEBBLEKIT = 'io.rebble.pebblekit2:client:1.2.0';
const COROUTINES = 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1';
// PebbleKit2 declares minSdk 24, so the app must target at least that.
const MIN_SDK = 24;

const pkgPath = 'com/owapp/companion';
const nativeSrcDir = join(root, 'native-android', 'java', pkgPath);
const destJavaDir = join(androidDir, 'app', 'src', 'main', 'java', pkgPath);

function patch(file, label, fn) {
  const before = readFileSync(file, 'utf8');
  const after = fn(before);
  if (after !== before) {
    writeFileSync(file, after);
    console.log(`  patched ${label}`);
  } else {
    console.log(`  ${label} already up to date`);
  }
}

// 0) variables.gradle: ensure minSdk meets PebbleKit2's requirement (>= 24).
patch(join(androidDir, 'variables.gradle'), 'variables.gradle (minSdk)', (src) =>
  src.replace(/minSdkVersion = (\d+)/, (m, v) => `minSdkVersion = ${Math.max(Number(v), MIN_SDK)}`),
);

// 1) Project-level build.gradle: add the Kotlin Gradle plugin classpath.
patch(join(androidDir, 'build.gradle'), 'build.gradle (kotlin classpath)', (src) => {
  if (src.includes('org.jetbrains.kotlin:kotlin-gradle-plugin')) return src;
  return src.replace(
    /(classpath 'com\.android\.tools\.build:gradle:[^']+'\n)/,
    `$1        classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:${KOTLIN_VERSION}'\n`,
  );
});

// 2) Module-level app/build.gradle: kotlin plugin, androidx.core pin, native deps.
patch(join(androidDir, 'app', 'build.gradle'), 'app/build.gradle', (src) => {
  let out = src;

  // Apply the Kotlin Android plugin right after the application plugin.
  if (!out.includes("apply plugin: 'org.jetbrains.kotlin.android'")) {
    out = out.replace(
      "apply plugin: 'com.android.application'\n",
      "apply plugin: 'com.android.application'\napply plugin: 'org.jetbrains.kotlin.android'\n",
    );
  }

  // Pin androidx.core to the version compatible with the installed compileSdk.
  // PebbleKit2 transitively requests a newer androidx.core that would force a
  // higher compileSdk than the SDK platform installed in the build image.
  if (!out.includes('resolutionStrategy')) {
    const force = `
// Pin androidx.core to a version that compiles against the installed Android SDK.
// PebbleKit2 transitively requests a newer androidx.core that would otherwise
// force a higher compileSdk than the one provisioned in the build image.
configurations.all {
    resolutionStrategy {
        force "androidx.core:core:$androidxCoreVersion"
        force "androidx.core:core-ktx:$androidxCoreVersion"
        // Keep kotlin-stdlib aligned with the Kotlin compiler (${KOTLIN_VERSION}); a
        // transitive dependency otherwise pulls a newer stdlib whose metadata
        // the ${KOTLIN_VERSION} compiler cannot read.
        force "org.jetbrains.kotlin:kotlin-stdlib:${KOTLIN_VERSION}"
        force "org.jetbrains.kotlin:kotlin-stdlib-jdk7:${KOTLIN_VERSION}"
        force "org.jetbrains.kotlin:kotlin-stdlib-jdk8:${KOTLIN_VERSION}"
    }
}

`;
    out = out.replace(/\ndependencies \{/, `\n${force}dependencies {`);
  }

  // Native runtime dependencies for the Pebble bridge.
  if (!out.includes(PEBBLEKIT)) {
    out = out.replace(
      /(implementation project\(':capacitor-cordova-android-plugins'\)\n)/,
      `$1    implementation "${PEBBLEKIT}"\n    implementation "${COROUTINES}"\n`,
    );
  }

  return out;
});

// 2b) AndroidManifest.xml: declare the PebbleKit2 listener service. This is
// REQUIRED for the selected Pebble phone app to register a companion session for
// our watchapp. Without it, every sendDataToPebble fails with
// FailedDifferentAppOpen even though our app is foreground on the watch.
patch(join(androidDir, 'app', 'src', 'main', 'AndroidManifest.xml'), 'AndroidManifest.xml (listener service)', (src) => {
  if (src.includes('com.owapp.companion.OwPebbleListenerService')) return src;

  // Ensure the `tools` namespace is available so we can suppress the
  // ExportedService lint warning on the intent-filtered service.
  let out = src;
  if (!out.includes('xmlns:tools=')) {
    out = out.replace(
      '<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
      '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n    xmlns:tools="http://schemas.android.com/tools">',
    );
  }

  const service = `
        <!-- PebbleKit2 listener: lets the Pebble phone app establish a companion
             session for our watchapp so phone->watch AppMessages can be sent. -->
        <service
            android:name="com.owapp.companion.OwPebbleListenerService"
            android:exported="true"
            tools:ignore="ExportedService">
            <intent-filter>
                <action android:name="io.rebble.pebblekit2.RECEIVE_DATA_FROM_WATCH" />
            </intent-filter>
        </service>
`;
  return out.replace(/\n    <\/application>/, `${service}    </application>`);
});

// 3) Copy the Kotlin sources in, replacing the generated Java MainActivity.
mkdirSync(destJavaDir, { recursive: true });
for (const name of readdirSync(nativeSrcDir)) {
  copyFileSync(join(nativeSrcDir, name), join(destJavaDir, name));
  console.log(`  copied ${name}`);
}
const generatedJava = join(destJavaDir, 'MainActivity.java');
if (existsSync(generatedJava)) {
  rmSync(generatedJava);
  console.log('  removed generated MainActivity.java');
}

console.log('Android resources ensured.');
