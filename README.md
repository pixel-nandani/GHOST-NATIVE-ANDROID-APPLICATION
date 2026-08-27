# Ghost

**An on-device screen-reading task agent for Android.** You state a goal in plain
language; Ghost reads what is actually on screen, decides one next tap, performs it, and
looks again — until the task is done. All planning runs locally on the phone's NPU. The
app does not hold the `INTERNET` permission.

Built for the iQOO Hackathon 2026 (Productivity / Open Innovation).

Design docs this implementation follows: [`docs/ghost-full-documentation.md`](docs/ghost-full-documentation.md)
and [`docs/ghost-build-plan.md`](docs/ghost-build-plan.md).

---

## What it does

```
Goal ──► PERCEPTION ──► PLANNING ──► SAFETY ──► ACTION ──┐
         (a11y tree)    (local LLM)   (gate)    (gesture) │
              ▲                                            │
              └──────────── loop until done ───────────────┘
```

One action per turn, re-reading the screen every time. That is the whole reliability
story: a popup, a slow load, or a keyboard covering the field it was about to tap all
get absorbed for free, because nothing is cached between turns. An upfront plan cannot
do that — it keeps executing stale decisions into a screen that has moved on.

---

## Quick start

```bash
# 1. Build (Android Studio: just open the folder and let it sync)
./gradlew assembleDebug

# 2. Run the tests that need no device — this is most of the logic
./gradlew test

# 3. Install
./gradlew installDebug

# 4. Side-load model weights (see "Model weights" below)
adb shell mkdir -p /data/local/tmp/ghost
adb push phi3-mini-4k-instruct-q4.task /data/local/tmp/ghost/model.task

# 5. Enable the two OS-level permissions — do this BEFORE demo day
adb shell am start -a android.settings.ACCESSIBILITY_SETTINGS
```

> **No Gradle wrapper JAR is committed.** Run `gradle wrapper` once, or just open the
> project in Android Studio and it will generate one on first sync.

---

## Setup: the two permissions that decide whether anything works

Neither can be granted from a runtime dialog. Both are OS-level toggles.

| Permission | Why | Where |
|---|---|---|
| `BIND_ACCESSIBILITY_SERVICE` | Read the screen, dispatch taps. **Required.** | Settings ▸ Accessibility ▸ Installed apps ▸ Ghost |
| `SYSTEM_ALERT_WINDOW` | Floating status bubble + kill switch. Optional but you lose the on-screen stop button. | Settings ▸ Apps ▸ Ghost ▸ Display over other apps |

Some OEM skins bury the accessibility toggle under *Downloaded apps*, *Installed
services*, or *Accessibility ▸ More*. **Find it on the actual loaner device before the
pitch, not on stage.** The in-app screen detects both states and deep-links to the right
settings page.

---

## Model weights

Not committed — too large and license-bound. Ghost looks for a MediaPipe `.task` bundle
in this order:

1. `/data/local/tmp/ghost/model.task` ← use this; adb-pushable without reinstalling
2. `<app files>/models/model.task`
3. `<external files>/model.task`

Recommended: **Phi-3-mini-4k-instruct, 4-bit** or **Gemma 2B (it) 4-bit**, in MediaPipe
`.task` format. Get them from the
[MediaPipe LLM Inference model list](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android#model).

### Running without weights

The app still runs. With no model on device it falls back to `HeuristicPlanner`, a
deterministic keyword matcher, and **says so** — a red banner in the app, and the overlay
reports the backend as `heuristic`.

This is deliberate, for two reasons: it lets perception, grounding and safety be built
and tested on a real device before the model integration lands (hours 2–10 of the build
plan), and it means a model that fails to load on the loaner phone degrades instead of
showing an error dialog mid-pitch. **If a judge asks whether the model is deciding, tell
them the truth** — the fallback is labelled everywhere precisely so that answer is easy.

---

## Architecture

The core is **pure Kotlin with no Android imports.** That is not stylistic: it is what
makes the interesting logic runnable under `./gradlew test` in milliseconds instead of
only on a phone.

```
app/src/main/kotlin/com/ghost/agent/
├── core/                          ← no Android dependencies, all JVM-testable
│   ├── model/       UiElement, Action, AgentEvent, TaskOutcome
│   ├── perception/  ScreenSerializer     — a11y tree → compact prompt text
│   ├── planning/    Planner, PromptBuilder, ActionParser, LlmPlanner, HeuristicPlanner
│   ├── safety/      SafetyPolicy, SafetyGate
│   └── agent/       AgentLoop, DeviceController, StepLog, GhostState
├── llm/             LlmEngine, MediaPipeLlmEngine   ← only file touching MediaPipe
├── service/         GhostAccessibilityService, AccessibilityDeviceController, GhostSession
├── overlay/         OverlayController                ← bubble + kill switch + confirm
└── ui/              MainActivity, MainScreen, SetupChecks, GhostTheme
```

### Files worth reading first

| File | Why it matters |
|---|---|
| `core/agent/AgentLoop.kt` | The whole control flow. Start here. |
| `core/planning/ActionParser.kt` | Surviving what small models actually emit. |
| `core/safety/SafetyGate.kt` | The only thing between the model and a real "Pay" tap. |
| `core/perception/ScreenSerializer.kt` | The model's entire view of the world. |
| `service/AccessibilityDeviceController.kt` | The only file that touches `AccessibilityNodeInfo`. |

### The action schema

```json
{"action":"tap|type|scroll|swipe|wait|back|open_app",
 "target_id": 1, "value": null, "done": false, "reason": "8 words max"}
```

`back` and `open_app` are additions to the schema in Appendix A of the design doc.
Without them a step-at-a-time loop has no legal way to leave a dead-end screen or cross
from one app to the next — which the headline two-app demo flow requires. Both are still
gated by the allow-list.

---

## Safety layer

Runs on **every** action, not once per task — a step-at-a-time agent can drift into a new
app between any two actions, so a check done at task start is stale by step two.

| Control | Default | Where |
|---|---|---|
| App allow-list | Gmail, Google Calendar, Chrome | `SafetyPolicy.DEMO` |
| Step cap | 15 actions, then auto-abort | `SafetyPolicy.stepCap` |
| Confirm before commit | send / submit / pay / delete / transfer / … | `SafetyGate.classify` |
| Kill switch | Always visible in the bubble | `OverlayController` |
| Failure streak abort | 3 consecutive failures | `SafetyPolicy.maxConsecutiveFailures` |

Two details that matter more than they look:

- **Keywords match on word boundaries.** A naive `contains("send")` fires on the word
  "Sender" in every email list row, and a confirmation that fires constantly is one users
  learn to tap through without reading — which costs more safety than it buys.
- **Only committing gestures are scanned.** Typing "delete my old account" into a search
  box is not a destructive act and must not prompt.
- **Everything fails closed.** Unknown package, blank package, empty allow-list → refused.

---

## Testing

```bash
./gradlew test          # ~90 JVM tests, no device needed
```

Covering the checklist from the build plan:

| Checklist item | Where |
|---|---|
| Schema parser unit tests, incl. malformed model output | `ActionParserTest` — fences, prose, quoted ints, two objects, truncation, empty |
| Node-tree dumps / element cheat sheet | `Fixtures.kt` — replace with real dumps from the loaner device |
| Safety layer correctness, both directions | `SafetyGateTest` — incl. the "Sender" false-positive trap |
| Full loop control flow | `AgentLoopTest` — step cap, hallucinated ids, declined confirm, app drift, kill switch |
| Latency benchmark | `StepLog` → logcat CSV after every run |

Still needs a real device (do these on the loaner):

- [ ] **Perturbation test** — dark mode, rotation, different app state. This is the whole
      reason the design matches labels instead of pixel coordinates; prove it.
      
- [ ] **Permission dry run** on the actual loaner skin.
- [ ] 
- [ ] **Latency numbers** — `adb logcat -s GhostService` prints a CSV per run.
- [ ] 
- [ ] **NPU vs CPU** — confirm the delegate from `adb logcat -s tflite:V` before putting a
      number on the metrics slide. MediaPipe does not report which one it chose.
      
- [ ] **Backup recording** of one clean full run.

---

## Known limitations

Stated plainly, because a judge will find them anyway and an honest answer is worth more
than a dodge.

- **Canvas-rendered apps expose nothing.** Games and some heavily-skinned apps return an
  empty accessibility tree. Ghost detects this and aborts with a clear reason rather than
  guessing. The VLM fallback is not implemented.
  
- **The model can hallucinate a `target_id`.** Handled, not prevented: the gate rejects
  it, the loop re-perceives and lets the model retry against a fresh element list. Costs
  a step.
  
- **Latency stacks.** Keep demo flows short.

- **Voice input may not be offline.** It uses the system `RecognizerIntent`, which on most
  devices is Google's cloud recognizer. **The offline claim covers the agent loop, not
  this optional input path.** Typed input is the primary path and is fully offline.
  
- **`GhostSession` is a process-wide singleton.** A production app would use DI. It is
  here because the OS owns the accessibility service's lifecycle, so the Activity can
  never hold a reference to it, and a Binder protocol is a lot of ceremony for one goal
  string and one boolean.
  
- **Sampling is not pinned yet.** See the day-one task at the top of
  `MediaPipeLlmEngine.kt` — default temperature ~0.8 is wrong for strict-JSON output and
  is the biggest single cause of parse retries.
  
- **No `FOREGROUND_SERVICE`.** Deliberate deviation from doc Section 6: an
  AccessibilityService is already a persistent bound system service, so adding one would
  be dead code.

---

## Attribution

Per the hackathon rules, all third-party components used:

| Component | License | Use |
|---|---|---|
| [MediaPipe LLM Inference API](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference) | Apache-2.0 | On-device LLM runtime, NPU/GPU delegate |
| [Phi-3-mini](https://huggingface.co/microsoft/Phi-3-mini-4k-instruct) *or* [Gemma 2B](https://huggingface.co/google/gemma-2b-it) | MIT / Gemma Terms | Planning model weights (4-bit, not committed) |
| AndroidX, Jetpack Compose, Material 3 | Apache-2.0 | UI |
| kotlinx.serialization, kotlinx.coroutines | Apache-2.0 | JSON parsing, concurrency |
| JUnit 4, Truth | EPL-1.0 / Apache-2.0 | Tests |

The AccessibilityService integration, perception serializer, prompt construction, action
parser, agent loop, grounding layer and safety gate in this repository were written for
this event. Public research on device-control agent loops (AppAgent, Android-in-the-Wild
and similar) informed the architecture; no agent application code was imported.

---

## Demo script (30–45s)

1. Type or speak the goal.
2. Bubble appears; Ghost starts tapping across apps, live, on screen.
3. Confirmation checkpoint fires on the commit action — **tap Allow on stage.** This is
   the beat that proves the safety layer is real and not decorative.
4. Final state: email sent / event created.
5. Metrics: per-step planning latency from `StepLog`, on-device only, zero network calls,
   zero API cost. Quote the measured number from logcat, not an estimate.
