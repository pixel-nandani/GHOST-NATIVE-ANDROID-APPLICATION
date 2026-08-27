# Ghost — On-Device Screen-Reading Task Agent
### Full build plan: stack, architecture, implementation, testing, resources
### iQOO Hackathon 2026 — Productivity / Open Innovation track

---

## 1. Core Decision: Stack

**Native Android (Kotlin), not Flutter/RN/PWA.**

Reason: the entire concept hinges on `AccessibilityService`, `dispatchGesture`, and `AccessibilityNodeInfo` tree reading — these are Android-only OS-level APIs. Flutter/RN would still need a native Kotlin plugin underneath to touch them, so wrapping adds overhead with zero benefit in a 30-hour window. PWA is explicitly disqualifying territory per the rules ("don't build a web-only app").

**Toolchain:**
- Android Studio (Green Light — needs laptop; use Office Kit screen mirror + remote control so the phone is still "in the loop" while you code)
- Kotlin + Coroutines
- Target SDK: whatever ships on the iQOO 15 loaner (check at Saturday check-in)

---

## 2. Architecture — 4 layers

```
[Voice/Text Command]
        │
        ▼
┌───────────────────┐
│ 1. PERCEPTION      │  AccessibilityService reads current screen's
│                    │  AccessibilityNodeInfo tree → serializes to
│                    │  compact text: [id, class, text, bounds, clickable]
└─────────┬──────────┘
          ▼
┌───────────────────┐
│ 2. PLANNING        │  Local model gets {goal, screen_state, history}
│  (on-device LLM/   │  → returns ONE next action as strict JSON:
│   VLM, NPU)         │  {action, target_id, value, done}
└─────────┬──────────┘
          ▼
┌───────────────────┐
│ 3. GROUNDING/ACTION│  Map target_id → real node bounds →
│                    │  dispatchGesture (tap/swipe) or
│                    │  performAction(ACTION_CLICK / ACTION_SET_TEXT)
└─────────┬──────────┘
          ▼
┌───────────────────┐
│ 4. SAFETY LAYER    │  App allow-list, step cap, confirm-before-
│                    │  submit checkpoint, on-screen kill switch
└────────────────────┘
          │
          └──► loop back to Perception until done=true or step cap hit
```

This is a **step-at-a-time agent loop**, not a full upfront plan — critical for reliability, since real UIs don't match a pre-planned script once something shifts (a popup, a loading state, a keyboard covering a field).

---

## 3. Model choice — pick the RIGHT level of ambition

### Recommended primary path (de-risked): **text-only, no vision**
Feed the model the serialized **accessibility node-tree text**, not a screenshot. Most stock Android apps (Gmail, Calendar, most forms) expose good content-description / text labels, so a small **text LLM** can plan actions without ever needing a VLM.

- **Model:** Phi-3-mini (3.8B, 4-bit) or Gemma 2B, quantized GGUF/LiteRT
- **Runtime:** MediaPipe LLM Inference API (fastest to integrate, handles tokenization/loading) or ExecuTorch (more NPU control, more "technical depth" points if you can show NPU delegate usage explicitly)
- **Why this is the right call for 30hrs:** avoids screenshot capture (`MediaProjection` permission dance), avoids VLM latency, and text-tree matching is inherently more robust than pixel-coordinate tapping — if UI shifts slightly, you're still matching by label/id, not screen position.

### Stretch upgrade (only if primary path is solid with hours to spare): **VLM fallback**
For icon-only buttons with no text label, fall back to a screenshot + small VLM (SmolVLM2-256M/500M-Instruct, or Qwen2-VL-2B if the NPU handles it) to visually identify the target. Wire this as a fallback path, not the default — keep the reliable text-tree path as your demo backbone.

---

## 4. Scope the demo — pick 2–3 flows, not "any app"

Pick apps with **stable, well-labeled** accessibility trees. Avoid canvas-rendered/custom-UI apps (games, some heavily-skinned apps) where the node tree comes back empty.

**Recommended flows:**
1. **Gmail** — compose + send an email to a named contact with a subject/body derived from the command
2. **Calendar (Google Calendar)** — create an event with title/date/time parsed from the command
3. *(stretch)* a simple **Forms** app or browser-based form — fill 2–3 known fields

These three chain nicely into your headline demo: *"renew my parking pass and email the receipt to accounts"* → fills a form → composes and sends an email — one command, two apps, fully on-device.

---

## 5. Safety layer (don't skip — this is also a judge-visible feature, not just risk mitigation)

- **Allow-list**: hardcode the 2–3 target package names; agent refuses to act outside them
- **Step cap**: hard limit (e.g., 15 actions) before auto-abort
- **Confirm-before-submit**: any action tagged "send," "submit," "pay," "delete" pauses for a one-tap user confirmation — this is also a great demo beat ("see, it asks before it actually sends")
- **Visible overlay**: a small floating status bubble showing "Ghost is acting…" + current step + a kill switch — doubles as your "creative phone use" visual for the 15%-weighted rubric line
- **State memory**: just an in-memory list of {action, result} passed back into each planning call — no need for a vector DB at this scope

---

## 6. Hour-by-hour build plan (30hr city battle, map to actual Red/Green schedule announced on-site)

| Hours | Work | Light |
|---|---|---|
| 0–2 | Project scaffold, initial commit + README, AccessibilityService boilerplate + permission request flow, Office Kit pairing | Green (setup) |
| 2–6 | Node-tree capture → serialize to compact text; manually dump trees for Gmail/Calendar to build your "element cheat sheet" | Green |
| 6–10 | Integrate on-device model (MediaPipe/ExecuTorch); test isolated prompt → JSON action loop with canned screen states (no real dispatch yet) | Green |
| 10–14 | Build grounding + dispatchGesture/performAction layer; get Flow A (Gmail) working end-to-end | Green→Red transition |
| 14–18 | Add Flow B (Calendar), safety layer (allow-list, step cap, confirm checkpoint, kill-switch overlay) | Red-friendly (test heavily on phone) |
| 18–22 | Voice input (optional: Android `SpeechRecognizer`, or local Whisper Tiny if time allows); polish overlay UI | Red |
| 22–26 | Stress-test flows repeatedly, log failure modes, add retries; record a clean backup demo video | Red |
| 26–30 | Buffer, pitch prep, repo cleanup, submit to Reskilll well before lock, rehearse live on phone | Red |

**Note:** writing Kotlin/AccessibilityService code needs Android Studio, but you can keep it Office-Kit-legitimate by coding via **remote control on the mirrored phone screen** rather than switching fully to laptop-only work — this also naturally maxes your Office Kit usage score since it's core to your workflow, not incidental.

---

## 7. Testing checklist

- [ ] **Schema parser unit tests** — feed canned model JSON outputs (including malformed ones) into the grounding layer independently of the model itself
- [ ] **Manual node-tree dumps** for each target app/screen state — catch cases where labels are missing before you're relying on them live
- [ ] **Perturbation test** — dark mode, rotated screen, slightly different app state — does text-based matching still find the right element? (This is the whole reason to prefer text-tree over raw coordinates.)
- [ ] **Latency benchmark** — log per-step inference time, NPU delegate vs CPU fallback — this is a strong, hard number for your pitch ("planning step: 380ms on-device, zero cloud calls")
- [ ] **Permission dry run** — verify AccessibilityService toggle-on flow works cleanly on the actual loaner device *before* demo day; some OEM skins bury this setting
- [ ] **Backup recording** — always have one clean, pre-recorded successful full run saved locally in case live state (notifications, popups) misbehaves mid-pitch

---

## 8. Resources

- Android AccessibilityService: https://developer.android.com/guide/topics/ui/accessibility/service
- `dispatchGesture` reference: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#dispatchGesture
- MediaPipe LLM Inference API (on-device Gemma/Phi): https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference
- ExecuTorch (Meta/PyTorch on-device runtime, NPU delegate path): https://pytorch.org/executorch
- SmolVLM (small vision-language model, if you build the VLM fallback): https://huggingface.co/HuggingFaceTB/SmolVLM
- Office Kit download: pc.vivoglobal.com

**On originality/compliance:** studying how public research/blog posts describe "Android agent" loop patterns is fine (it's how you'd learn any architecture) — but the AccessibilityService integration, planning loop, and grounding code must be written fresh during the event window. Cite MediaPipe/ExecuTorch/model weights in your README (open-source libs are fine with attribution per the rules) — just don't import a pre-built agent *app*.

---

## 9. Pitch beat (30–45 sec live demo)

1. Speak/type the command
2. Overlay shows the parsed plan
3. Phone auto-executes taps across 2 apps live, on-screen
4. Confirm-before-submit checkpoint fires once (proves the safety layer isn't decorative)
5. Final state shown (email sent / event created)
6. Cut to one metrics slide: on-device only, NPU inference time per step, zero cloud calls, zero API cost

That combination — live agentic execution + a hard NPU latency number + a visible safety checkpoint — hits novelty, technical depth, and creative phone use in one demo beat.
