# MAD Assistant v3.0.0 — Direct APK Download & Setup

**Sushil ke liye ready-made app.** GitHub Actions har push par APK banata hai.
Latest build **green** hai — poore 21 commits ke saare features included.

## 📲 APK seedha download karo

1. Is page par jao: **Releases**
   https://github.com/sheokandsushil02-lgtm/Mad-assistant-/releases
2. Latest release me `mad-assistant-release.apk` (ya `mad-assistant-debug.apk`) par click karo.
3. Download hote hi phone me kholo → "Install from unknown sources" allow karo → Install.

> Agar Releases me abhi kuch nahi hai: **Actions** tab → "Build MAD Assistant APK" →
> latest green run → Artifacts → `mad-assistant-debug` / `mad-assistant-release` download
> (login ke saath ZIP me milega, unzip karke APK install karo).

## ✅ Setup (first launch)

1. **Select Target App** — jo app MAD ko control karni hai (e.g. REMS Business) select karo.
2. **Start Assistant** dabao — mic/notification permission allow karo.
3. Settings me permissions dashboard: Microphone, Overlay, Battery optimization sab 🟢 karo.

## 🎤 Kaise use kare

- "Hey Mad" (variants: himed, hi med, hey med, heed, hey mate, a mad, mad, मैड) bolo → "हाँ, बोलिए" → command bolo.
- **One-shot**: "Hey Mad rems business kholo" → seedha command chalega.
- Mic/AI/TTS live status debug console main screen par dikhta hai.
- Background listening disconnect ho to **🛟 Fallback Google Voice Popup** button dabao.

## 🔧 Troubleshooting

| Problem | Fix |
|---|---|
| Mic sunta nahi | App restart + battery optimization disable |
| Reply aata hai par bolta nahi | Settings → Test Voice → Google TTS engine check |
| "AI: waiting for command" par atakta hai | Google app (com.google.android.googlequicksearchbox) update karo |

## Features (v3.0.0)

- Natural Google TTS voice (hi-IN/en-IN network/HD voices, pitch 1.0, rate 0.95)
- Wake-word variants + one-shot commands + two-step command flow
- SpeechRecognizer destroy+recreate on BUSY/CLIENT/SERVER_DISCONNECTED(11)
- Live debug console (Mic / AI / TTS)
- Fallback Google Voice Popup
- Multi-provider AI brain (Gemini/OpenAI/Claude/Grok) + saved API key

Built with Kotlin, Gradle 8.9, AGP 8.6.0, Kotlin 1.9.24, compileSdk 34, minSdk 26.
