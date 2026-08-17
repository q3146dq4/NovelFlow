# NovelFlow v0.1 — Screen-off narration / chapter transition fix

- Keeps Chunk/TTS behavior unchanged.
- Adds `PARTIAL_WAKE_LOCK` while narration is active or waiting for the next chapter.
- Releases the lock on stop/close/destroy.
- Removes the fixed 500 ms next-chapter delay in favor of a retrying navigation state.
- Detects a next-chapter href when available and loads it directly; otherwise falls back to clicking the next-chapter control.
- Waits for the new viewer page and retries TTS body collection for up to 60 attempts.
- Reloads the page after repeated collection failures during chapter transition.
- Prevents duplicate chapter navigation attempts.
- Preserves the existing NovelFlow package ID, v0.1, branding, TTS regex, and external TTS integration.
