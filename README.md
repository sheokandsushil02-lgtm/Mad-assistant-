# MAD Assistant — Step 1 Core Foundation

Always-on AI Voice Assistant for Rajat Electro-Mech Solutions.
Built with Kotlin, Gradle 8.9, AGP 8.6.0, Kotlin 1.9.24, compileSdk 34, minSdk 26.

## Features (Step 1 skeleton)
- 24/7 foreground service (microphone type) with persistent notification
- Boot receiver to auto-restart after reboot
- **Select Target App** — user picks any installed app (e.g., REMS Business) for the agent to control
- Accessibility service bridge (`RemsBridgeAccessibilityService`) that reacts only to the selected target app
- Room (persistent memory), Retrofit/OkHttp (AI brain), Vosk (wake word) dependencies wired up

## One-time setup needed
The GitHub Actions workflow file must be created manually (token scope limitation):
1. Open `github-workflows/build-apk.txt` and copy its full content
2. GitHub → Add file → Create new file → name it `.github/workflows/build-apk.yml`
3. Paste the content → Commit
4. Actions tab → "Build MAD Assistant APK" will run automatically

## APK output
After a successful Actions run, download `mad-assistant-debug` from the run's Artifacts.
