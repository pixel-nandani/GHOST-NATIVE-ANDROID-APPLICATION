# Ghost — Full Application Documentation
### On-device screen-reading task-execution agent
### iQOO Hackathon 2026 — Productivity / Open Innovation

---

## 1. Overview

**One-liner:** Ghost is an on-device agent that reads your phone's screen, decides what to tap next using a local language model, and carries out multi-step tasks across a small set of apps — entirely offline, entirely on the Snapdragon NPU, with no cloud calls at any point.

**The problem it solves:** A lot of "productivity work" on a phone isn't hard, it's just tedious and multi-app — renew a document in one app, then email the receipt in another, then log it in a third. Every existing "AI phone assistant" either answers questions (chatbot) or requires you to pre-record a fixed macro (script recorder, breaks the moment a UI changes). Ghost sits in between: you state a goal in plain language, and it figures out and performs the actual sequence of taps itself, adapting as each screen changes.

**What Ghost is not:**
- Not a chatbot — it doesn't just describe what to do, it does it
- Not a fixed macro/script recorder — there's no pre-recorded tap sequence to break when a button moves
- Not a cloud RPA tool — every inference call happens on-device; there is nothing to intercept over the network because nothing is sent over the network

---

## 2. Core Concept: the agent loop

Most "AI does X for you" demos work by asking a model to output one big plan upfront ("step 1, step 2, step 3...") and then blindly executing it. That's fragile on a real phone: a popup appears, a field is already filled, an app takes an extra second to load — and the fixed plan breaks.

Ghost instead uses a **step-at-a-time agent loop**: at every turn, it looks at what's actually on screen *right now*, decides on exactly **one** next action, performs it, and only then looks again. This is the same pattern used in academic "device-control agent" research (e.g. AppAgent/Android-in-the-Wild-style setups), applied here as a from-scratch, offline, hackathon-scoped implementation.

```
        ┌─────────────────────────┐
        │   User states a goal     │
        │  "renew my parking pass  │
        │   and email the receipt  │
        │   to accounts"           │
        └────────────┬─────────────┘
                      ▼
        ┌─────────────────────────┐
        │  1. PERCEPTION           │◄────────────┐
        │  Read current screen     │              │
        │  as structured text      │              │
        └────────────┬─────────────┘              │
                      ▼                            │
        ┌─────────────────────────┐               │
        │  2. PLANNING             │               │
        │  Local model decides     │               │
        │  ONE next action (JSON)  │               │
        └────────────┬─────────────┘               │
                      ▼                             │
        ┌─────────────────────────┐                │
        │  3. GROUNDING & ACTION   │                │
        │  Map action → real       │                │
        │  tap / swipe / text entry│                │
        └────────────┬─────────────┘                │
                      ▼                              │
        ┌─────────────────────────┐                 │
        │  4. SAFETY CHECK         │                 │
        │  Confirm if risky,       │                 │
        │  else continue           │                 │
        └────────────┬─────────────┘                 │
                      │                               │
             done? ── No ─────────────────────────────┘
                      │
                     Yes
                      ▼
             ┌─────────────────┐
             │  Task complete    │
             └───────────────────┘
```

Everything in this loop except step 2 (the model call) is deterministic Android code — reading a UI tree and dispatching a tap are not AI operations, they're standard OS APIs. This is why Ghost can run fully offline: there is exactly one place a model runs, and it runs locally.

---

## 3. Component deep-dive

### 3.1 Perception layer — reading the screen without a screenshot

Android's `AccessibilityService` API (originally built for screen readers) exposes the current screen as a tree of `AccessibilityNodeInfo` objects. Each node carries:
- `text` / `contentDescription` — visible or accessibility label text
- `className` — what kind of element it is (Button, EditText, CheckBox, etc.)
- `boundsInScreen` — its on-screen rectangle
- `isClickable`, `isEditable`, `isFocused` — interaction affordances

Ghost walks this tree on every loop iteration and serializes it into a compact text representation — not an image. Example, for a "renew pass" screen:

```
[1] Button "Renew Now" clickable bounds=(40,220,340,280)
[2] EditText "Vehicle number" editable bounds=(40,140,340,190)
[3] TextView "Expires: 12 Sept 2026" bounds=(40,80,340,110)
[4] Button "Cancel" clickable bounds=(40,300,180,350)
```

**Why text, not vision:** node labels stay stable across minor UI shifts (dark mode, slightly different scroll position, a redraw), whereas raw pixel coordinates don't. A model reasoning over labels is inherently more robust than one reasoning over a screenshot and guessing pixel coordinates.

### 3.2 Planning layer — the only AI step in the whole loop

Each turn, the on-device model receives three things:
1. The original goal (unchanged throughout the task)
2. The current serialized screen state (from 3.1)
3. A short history of actions already taken this task

It returns **exactly one** next action as strict JSON — never free text, never a multi-step plan:

```json
{
  "action": "tap",
  "target_id": 1,
  "value": null,
  "done": false
}
```

or, for text entry:

```json
{
  "action": "type",
  "target_id": 2,
  "value": "MH31AB1234",
  "done": false
}
```

When the goal is fully satisfied, the model instead returns `"done": true`, which ends that sub-task (e.g. the renewal), and Ghost moves to the next sub-goal (e.g. opening Gmail).

**Why one action at a time, not a full upfront plan:** if step 3 unexpectedly triggers a popup, an upfront plan has no way to react — it just keeps executing stale coordinates into the wrong screen. A step-at-a-time loop re-reads the actual state every turn, so it naturally handles popups, loading delays, and minor layout differences without special-casing them.

**Model used:** a small on-device LLM — Phi-3-mini (3.8B, 4-bit quantized) or Gemma 2B — running via the Snapdragon NPU delegate. Text-only, no vision, is the primary path (see Section 8 for why).

### 3.3 Grounding & action layer — turning JSON into a real tap

The `target_id` in the model's JSON output refers to the numbered element from the serialized screen (Section 3.1). Grounding maps that id back to the *actual* `AccessibilityNodeInfo`, then performs the real action:
- **Tap:** `dispatchGesture()` with a short tap gesture at the node's screen bounds, or `performAction(ACTION_CLICK)` directly on the node if it's accessibility-clickable (more reliable when available)
- **Type:** `performAction(ACTION_SET_TEXT)` on an editable node, passing the `value` string
- **Scroll/swipe:** `dispatchGesture()` with a swipe path

This is the layer that makes Ghost an *agent* rather than a description generator — the phone is the one taking the action, not the user copying instructions.

### 3.4 Safety layer — the gate before anything irreversible

Because a local model choosing real actions on your phone is inherently higher-stakes than a chatbot answering a question, Ghost includes a dedicated safety layer that runs on every single action, not just at the end:

- **App allow-list:** Ghost will only act inside a small, hardcoded set of target apps (e.g. Gmail, Calendar). Any attempt to act outside that list is refused.
- **Step cap:** a hard ceiling (e.g. 15 actions) on how many steps one task can take before auto-aborting — prevents runaway loops.
- **Confirm-before-submit:** any action classified as send / submit / pay / delete pauses execution and shows a one-tap confirmation to the user before proceeding. Everything else (typing, scrolling, opening a menu) runs straight through.
- **Kill switch + status overlay:** a small floating bubble is visible on screen at all times Ghost is acting, showing the current step and an always-available stop button.

This layer is not just risk mitigation — it's also a visible, demoable feature: showing the confirmation checkpoint fire live proves the safety design isn't decorative.

### 3.5 State / memory layer

A simple in-memory list of `{action, result}` pairs from the current task, passed back into the planning prompt each turn so the model has short-term context ("I already tapped 'Renew Now', so don't tap it again"). No persistent database or vector store is needed at this scope — the memory only needs to last for the duration of one task.

---

## 4. End-to-end walkthrough (concrete example)

**User command:** *"Renew my parking pass and email the receipt to accounts@company.com"*

| Turn | Screen state (simplified) | Model output | Action taken |
|---|---|---|---|
| 1 | Parking app home, "Renew Now" button visible | `{"action":"tap","target_id":1}` | Taps "Renew Now" |
| 2 | Form with empty vehicle number field | `{"action":"type","target_id":2,"value":"MH31AB1234"}` | Types vehicle number |
| 3 | Form filled, "Submit" button visible | `{"action":"tap","target_id":3}` | **Safety layer intercepts** — "Submit" is a flagged action → user confirms once |
| 4 | Confirmation screen, receipt visible | `{"action":"tap","target_id":1,"value":null,"done":true}` (sub-goal 1 complete) | Sub-task marked done |
| 5 | Gmail opened, compose screen | `{"action":"type","target_id":2,"value":"accounts@company.com"}` | Fills recipient |
| 6 | Compose screen, subject empty | `{"action":"type","target_id":3,"value":"Parking pass renewal receipt"}` | Fills subject |
| 7 | Compose screen, "Send" visible | `{"action":"tap","target_id":5}` | **Safety layer intercepts again** — "Send" confirmation |
| 8 | Email sent confirmation | `{"action":"tap","target_id":1,"done":true}` | Task complete |

Two safety checkpoints fire naturally in this single command — exactly the moments a user would want a pause before something irreversible happens.

---

## 5. Tech stack

| Layer | Technology | Why |
|---|---|---|
| Language / platform | Kotlin, native Android | `AccessibilityService`, `dispatchGesture`, and NPU delegates are Android-only OS-level APIs; wrapping them in Flutter/RN adds overhead with no benefit here |
| Screen reading | `AccessibilityService` + `AccessibilityNodeInfo` tree | Native OS API, no screenshot/vision needed for the primary path |
| Action execution | `dispatchGesture()`, `performAction()` | Native OS API for simulating taps, swipes, text entry |
| On-device inference runtime | MediaPipe LLM Inference API (primary) or ExecuTorch (more NPU control) | Both delegate to the Snapdragon NPU via QNN |
| Planning model | Phi-3-mini (4-bit) or Gemma 2B, quantized GGUF/LiteRT | Small enough for real-time-ish loop latency on-device |
| Voice input (optional) | Android `SpeechRecognizer`, or local Whisper Tiny | Fallback: plain text input is always available for demo safety |
| Dev workflow | Office Kit (screen mirror, remote control, clipboard, file transfer) | Lets the team code on a laptop while the phone stays the live target device — required build surface |
| IDE | Android Studio | Standard native Android tooling |

---

## 6. Android permissions & manifest requirements

- `BIND_ACCESSIBILITY_SERVICE` — core permission; requires manual user toggle in system Settings (not grantable via a runtime dialog), so this must be walked through once during setup/demo prep
- `SYSTEM_ALERT_WINDOW` — for the floating status/kill-switch overlay
- `RECORD_AUDIO` — only if voice input is implemented
- `FOREGROUND_SERVICE` — recommended so the accessibility/planning loop isn't killed by the OS mid-task
- **`INTERNET` is notably *not* required for the core loop** — this is a genuine pitch point: once the model weights are on-device, the entire agent loop, including planning, needs zero network access. (Model weights themselves are bundled into app storage ahead of time — see Section 8.)

---

## 7. Model & NPU integration details

**Primary path — text-only, no vision (recommended default):**
Most stock apps (Gmail, Calendar, most forms) expose good accessibility labels, so a small **text LLM** can plan actions from the serialized node tree alone, without ever capturing a screenshot. This avoids the `MediaProjection` screenshot permission flow, avoids vision-model latency, and — because it matches by label rather than pixel position — is inherently more robust to minor UI shifts.

**Stretch path — VLM fallback (only if time allows):**
For icon-only buttons with no usable text label, a fallback captures a screenshot and uses a small vision-language model (SmolVLM2-256M/500M-Instruct, or Qwen2-VL-2B if the NPU handles it comfortably) to visually identify the target element. This is wired as a fallback, not the default — the reliable text-tree path remains the demo backbone.

**Runtime comparison:**

| Runtime | Integration speed | NPU control | Best for |
|---|---|---|---|
| MediaPipe LLM Inference API | Fast — handles tokenization/loading | Good, less granular | Getting a working loop quickly |
| ExecuTorch | Slower to wire up | Fine-grained NPU delegate control | Demonstrating explicit NPU utilization for judges |

**Quantization:** 4-bit weights (GGUF or LiteRT format) to fit comfortably in phone RAM and get real NPU throughput rather than falling back to CPU.

**Latency:** expect to benchmark and report per-step planning latency live (e.g. "~300-400ms per planning step, on-device, zero cloud calls") — this is a concrete, judge-friendly number that other teams calling cloud APIs simply cannot produce.

---

## 8. User journey (UX flow)

1. User opens Ghost, grants `AccessibilityService` permission (one-time system Settings toggle)
2. User types or speaks a goal (e.g. "renew my parking pass and email the receipt to accounts")
3. A floating status bubble appears: "Ghost is working…"
4. Ghost silently reads the screen, plans, and executes taps — the bubble updates with the current step in plain language ("Filling vehicle number…")
5. On a flagged action (send/submit/pay/delete), execution pauses and a small confirmation dialog appears; user taps once to continue
6. Bubble shows "Done" when the model reports task completion, or an error state with a clear reason if the step cap or allow-list is hit
7. Kill switch is available in the bubble at every step, at all times

---

## 9. Testing & validation strategy

- **Schema parser unit tests** — feed the grounding layer canned model JSON outputs (including deliberately malformed ones) independently of the model, to confirm it fails safely rather than crashing
- **Manual node-tree dumps** — capture and review the actual accessibility tree for each target app/screen ahead of time, to catch missing labels before relying on them live
- **Perturbation testing** — dark mode, rotated screen, slightly different app state — confirm text-based matching still finds the right element (this is the core reason the design favors label-matching over coordinate-matching)
- **Latency benchmarking** — log per-step inference time, NPU delegate vs. CPU fallback, for the pitch's metrics slide
- **Permission dry run** — verify the `AccessibilityService` toggle-on flow works cleanly on the actual device well before demo time; some OEM skins bury this setting in non-obvious places
- **Backup recording** — keep one clean, pre-recorded successful full run saved locally in case live app/notification state misbehaves mid-pitch

---

## 10. Known limitations & risks

- **Non-standard UI apps:** apps that render their UI on a canvas (many games, some heavily custom-skinned apps) expose little or nothing in the accessibility tree — these are out of scope for the text-only path and would need the VLM fallback
- **Model hallucination of `target_id`:** the model could reference an id that doesn't exist in the current screen; the grounding layer must validate this and fail safely (retry perception, or abort the step) rather than crash
- **Latency stacking:** a task with many steps accumulates per-step planning latency; scope demo flows to a small number of steps to keep the live demo tight
- **Permission friction:** `AccessibilityService` requires a manual, one-time OS-level toggle — this must be done *before* the live demo, not during it
- **One-time setup requires connectivity:** model weights need to be on-device before the offline loop can run; downloading/bundling them is a one-time setup step, not a runtime dependency

---

## 11. Hackathon rule compliance mapping

| Rule | How Ghost satisfies it |
|---|---|
| Must run and demo on the iQOO phone | Entire agent loop — perception, planning, grounding, safety — executes on-device; nothing is laptop-only at demo time |
| Local/open-source model at the core | Phi-3-mini/Gemma 2B (or SmolVLM for the stretch path) running via on-device NPU delegate is the only decision-making component |
| Office Kit usage | Screen mirror + remote control used throughout development so the phone stays the live target while coding happens on the laptop |
| Original work, built in the event window | AccessibilityService integration, planning loop, and grounding logic are written fresh during the event; only libraries/runtimes (MediaPipe/ExecuTorch, model weights) are pre-existing, open-source, and will be credited |
| Attribution for open-source components | README explicitly credits MediaPipe/ExecuTorch and the specific model checkpoints used |
| No pre-built app carried in | Nothing here is a repackaged existing product — this is a from-scratch agent loop scoped to 2-3 demo flows |

---

## 12. Future scope (beyond the hackathon)

- Expand the VLM fallback to cover icon-heavy, non-standard apps
- Let users "teach" Ghost a new app flow once, generalizing beyond the initial allow-list
- Scheduled/recurring goals ("do this every Monday morning")
- On-device learning from user corrections (if a confirmation is repeatedly declined, adjust future planning)
- Multi-turn conversational goal refinement before execution starts

---

## 13. Glossary

- **AccessibilityService** — Android OS API (originally for screen readers) that exposes the current screen as a tree of labeled, interactable elements
- **Agent loop** — a control pattern where a model repeatedly observes state, decides one action, and acts, rather than planning everything upfront
- **Grounding** — mapping an abstract model decision (e.g. "tap the submit button") to a concrete, executable action on a real UI element
- **NPU** — Neural Processing Unit; dedicated on-chip hardware (here, on the Snapdragon SoC) for running model inference efficiently and offline
- **Quantization** — compressing a model's weights (e.g. to 4-bit) so it fits and runs efficiently on-device
- **VLM** — Vision-Language Model; a model that reasons over images (screenshots) in addition to text

---

## 14. Resources & references

- Android AccessibilityService guide: https://developer.android.com/guide/topics/ui/accessibility/service
- `dispatchGesture` reference: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#dispatchGesture
- MediaPipe LLM Inference API: https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference
- ExecuTorch (on-device runtime, NPU delegate path): https://pytorch.org/executorch
- SmolVLM (small vision-language model, for the stretch fallback): https://huggingface.co/HuggingFaceTB/SmolVLM
- Office Kit: pc.vivoglobal.com

---

## Appendix A — Full action JSON schema

```json
{
  "action": "tap | type | scroll | swipe | wait",
  "target_id": "integer, references a numbered element from the current serialized screen",
  "value": "string or null — required for 'type', unused otherwise",
  "done": "boolean — true only when the current sub-goal is fully complete"
}
```

## Appendix B — Sample serialized screen (input to the planning model)

```
GOAL: Renew my parking pass and email the receipt to accounts@company.com
HISTORY: []
CURRENT SCREEN:
[1] Button "Renew Now" clickable bounds=(40,220,340,280)
[2] EditText "Vehicle number" editable bounds=(40,140,340,190)
[3] TextView "Expires: 12 Sept 2026" bounds=(40,80,340,110)
[4] Button "Cancel" clickable bounds=(40,300,180,350)
```
