# NovelRegEx v0.1 — screen-off binge playback v2

This revision keeps the existing TTS path intact and adds a chapter preloader so the next chapter's text can be prepared while the current chapter is still playing. The main WebView remains the visible viewer; a tiny attached WebView loads the next episode in parallel.

Changes:
- Keep PARTIAL_WAKE_LOCK during playback and chapter transition.
- Keep a Wi-Fi lock during playback/transition to reduce Wi-Fi sleep during screen-off playback.
- Preload the next chapter URL while the current chapter is being read.
- Collect the preloaded chapter text before the current chapter ends when possible.
- If the preloaded text becomes ready while waiting for the next chapter, start TTS immediately without waiting for the visible WebView's DOM to finish loading.
- Preserve the existing fallback next-chapter navigation and reload watchdog.
- Do not change the working TTS engine/API path.
