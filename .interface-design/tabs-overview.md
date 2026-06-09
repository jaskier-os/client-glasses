# Glasses Client -- Tabs UI & Feature Reference

This document describes every tab/screen in the Rokid AR Lite glasses client, intended as a brief for a designer to redesign each surface against the existing design system (`system.md`, attached at the end).

## Hardware & input model (read first)

- **Display**: 480x640 monochrome green micro-LED waveguide. Black = transparent, any non-black = green light. No alpha, no real color, only luminance levels.
- **Inputs**: capacitive temple touchpad emitting only discrete keycodes (DPAD_LEFT/RIGHT/CENTER/BACK + a few F-keys). No pointer, no coordinates. One physical camera button. **No volume keys.**
- **Daemon-paced scrolling**: a native daemon converts touchpad gestures to KP0/KP1 stepping, mapped to DPAD_RIGHT/LEFT in the app. Designs must work as a one-axis carousel + tap to confirm.
- **Wear sensing**: input is gated by "is being worn" -- ghost states should not fire when off-head.
- **Two processes**: `ListenerService` (backend, audio/BT/camera/ReID/recording/TTS) drives `MainActivity` (UI) via broadcasts. UI is a thin renderer of state.

## Global chrome (present on every tab)

A thin status row sits at the top, a centered pill tab bar at the bottom, and the active tab fills everything in between edge-to-edge.

### Top status bar
Components shown only when relevant:
- Current time (HH:MM, monospace).
- Battery: numeric percentage + glyph; lightning bolt overlay when charging or full.
- WiFi indicator (visible only when WiFi enabled).
- Weather: small icon + temperature in degrees, set from a backend broadcast.
- BT-connected dot, orchestrator-connected dot.
- Recording indicator: `ic_video_rec` (active) or `ic_video_rec_paused`.
- Debug status text (dev builds, 9-10sp, ghost luminance).
- "Listening..." / "Thinking..." status block with a small icon (mic / pulsing dot). During Listening an `AudioVisualizerView` (32 vertical equalizer bars, animated heights) renders below the status text. During Thinking the status text pulses between mid and glow.
- "Double-tap again to stop" hint that flashes during LISTENING/RESPONDING.

### Bottom tab pill
- 190dp x 18dp pill, centered horizontally at the bottom edge.
- `--void` fill, hairline `--glow` border, 13dp corner radius.
- A sliding `--ghost` highlight capsule animates between slots (200ms ease-out). Selected icon brightens to `--glow`, others sit at `--ghost`.
- All icons filled style, 13dp inside 38dp slots.
- When the tab bar gains focus (vs. content focus) its border thickens.

### System overlays
Two `WindowManager` overlays float above any tab:
- **NotificationOverlay**: phone notification mirror. Sender label + message text in a rounded box, configurable display duration.
- **CallOverlay**: incoming-call modal. Phone glyph, contact name row, number beneath, glow-outlined rounded box, helper hint text.

---

## Tab catalogue

The `TabId` enum: `MUSIC, CHAT, CHAT_LIST, TELEGRAM, REID, TODO, NIGHTVISION, TRANSLATE, MAP, TELEPROMPTER, MOUSE`.

Default visible (in this exact order): **TODO, CHAT, CHAT_LIST, TELEGRAM, REID** (CHAT is the default selected index). The remaining tabs (MUSIC, NIGHTVISION, TRANSLATE, MAP, TELEPROMPTER, MOUSE) appear/disappear dynamically when their feature activates.

---

### 1. TODO tab (default-active, index 0)

**Purpose**: at-a-glance personal queue -- tasks, saved messages, scheduled jobs, alarms.

**Layout**:
- A second-level **subtab pill** at the top of the tab area, same width as the main pill (4 slots, centered). Icons only, no labels: checklist (Tasks), bookmark (Saved), schedule/lightning (Jobs), alarm (Alarms).
- The body below is one of four RecyclerViews -- `todoChecklistRecycler`, `todoSavedRecycler`, `todoJobRecycler`, `todoAlarmRecycler` -- swapped per subtab.
- A full-screen "todo message overlay" can replace the body to show a long detail view of one item (e.g. expanded saved-message text), with its own ScrollView.

**Subtab content shapes**:
- **Tasks** -- checklist items (TodoChecklistAdapter). Each row: title + done indicator.
- **Saved** -- mirror of Telegram "Saved Messages" (TelegramSavedAdapter / TelegramMessage).
- **Jobs** -- scheduled background jobs (JobDisplayAdapter).
- **Alarms** -- alarms list (AlarmDisplayAdapter).

**Focus model**: `TODO_FOCUSED` with two levels (`todoFocusLevel`): `0` = subtab nav (DPAD_LEFT/RIGHT cycles subtab, CENTER drills in), `1` = content scroll/select inside the active subtab.

**Designer notes**: this is the most "list-heavy" tab. Each list type has a different mental model -- treat them as four distinct visual treatments inside a shared frame, not one generic list. Selected row highlight animates a 0->1dp ghost border.

---

### 2. CHAT tab (default-active, index 1, the home screen)

**Purpose**: live conversation with the AI assistant. Voice in (via phone wake-word + transcription), text/TTS out.

**Layout**:
- Status bar shows mic/pulse during a conversation; otherwise minimal.
- Main area is a vertical RecyclerView of `ChatMessage` items.
- Optional progress bar (intermediate spinner) between bubbles.
- "+ New chat" affordance sits in the chat-list lane but is reachable via TAB_NAV.

**Message types (`ChatMessage.Role`)** with bubble treatments per system.md:
- `USER` -- right-aligned, `--trace` rounded fill, `--bright` text, 12dp radius, no border.
- `ASSISTANT` -- left-aligned, no fill, `--mid` text, plain.
- `TOOL` -- italic 11sp, `--dim`, optional small wrench icon, used for "searching the web", "reading file", etc.
- `SYSTEM` -- centered ghost metadata.
- Image attachments: a `TYPE_IMAGE` viewholder renders an inline thumbnail (from `imageBitmap`), used when a user message attaches a captured photo.

Each message carries `requestId`, `timestamp`, optional `responseTimeMs` and `tokenCount` (rendered as supporting metadata).

**State transitions** (driven by `ListenerService` `EXTRA_STATE` broadcasts):
- `IDLE`: clear all temp messages ("listening", "pending", "partial"), hide visualizer, hide spinner.
- `LISTENING`: show "Listening..." + glow mic + audio visualizer + double-tap hint, auto-focus chat.
- `RESPONDING`: show "Thinking..." + dot pulse, start a stopwatch timer that displays elapsed time.

**Center-message highlight**: when the chat is focused, the message nearest the viewport center gets a 0.5dp `--ghost` border that tracks scroll in real time.

**Designer notes**: this is the surface seen the most. Treat density carefully -- tool messages are noise, assistant messages are the signal. The "Thinking..." pulse and visualizer should breathe, not strobe.

---

### 3. CHAT_LIST tab (default-active, index 2)

**Purpose**: history of past conversations + entry to start a new one.

**Layout**:
- RecyclerView of `ChatSummaryItem` rows (id, title, relativeTime, ...).
- A "+ New chat" header row at top (DIM 13sp text on void).
- Active conversation row: title in `--glow` (vs. default `--mid`).
- Selected row: animated 0->1dp ghost border, 8dp radius.
- Title `--mid` 13sp, subtitle/relativeTime `--dim` 11sp.

**Focus model**: `LIST_FOCUSED` -- DPAD scroll, CENTER opens that conversation (which then loads history into the CHAT tab and switches there).

**Designer notes**: rows are minimal by design -- title + time. Resist adding metadata; the chat tab is where detail lives.

---

### 4. TELEGRAM tab (default-active, index 3)

**Purpose**: read/reply to Telegram chats hands-free. Has the most internal modes of any tab.

**Sub-states (each is a distinct FocusState)**:
- `TELEGRAM_AUTH` -- "Speak to unlock" gating screen. A vertical centered LinearLayout with `telegramAuthPrompt` ("Speak to unlock", `--mid`) and an `AudioVisualizerView` added programmatically below it. Used for speaker verification before exposing private chats.
- `TELEGRAM_LIST_FOCUSED` -- list of chats (TelegramChat: chatId, title, lastMessage, lastMessageDate, lastMessageSender, unreadCount).
- `TELEGRAM_TOPICS_FOCUSED` -- when a forum/super-group is opened, list of topics (TelegramTopic: id, title, unreadCount, lastMessageDate).
- `TELEGRAM_CHAT_FOCUSED` -- the messages inside a chat/topic.
- `TELEGRAM_RECORDING` -- voice-message recording state; visualizer + countdown.
- `TELEGRAM_PREVIEW` -- preview before send. Includes a `telegramSendCountdown` TextView ("Sending in 2s...") to allow last-second cancel.

**Designer notes**: the sequence Auth -> List -> (Topics ->) Chat -> Record -> Preview is a "drill-down then commit" flow. Each level deserves a clear visual depth cue using luminance only (active level glow, prior levels fade to ghost). Unread counts should be the brightest element in the list view.

---

### 5. REID tab (default-active, index 4)

**Purpose**: on-device face recognition. The wearer sees who is in front of them, with a name label and optional intel modal.

**Layout (REID_FOCUSED / REID_FACES_FOCUSED)**:
- Status area shows ReID engine state ("RUNNING", "STOPPED", ...) with `ic_play` / `ic_stop` start/stop control.
- A horizontal **face bar** (`reidFaceBar`) with up to 7 thumbnail tiles (56dp each, 8dp rounded, 2dp border). Thumbnails come from face crops; best-quality stored thumb wins. Selection slides through with a left-aligned window so the selected face stays visible.
- Below the bar, a `reidFaceIdLabel` shows the resolved identity / metadata of the selected face.
- `REID_INTEL_MODAL`: a centered modal (`reidIntelContent`) loads richer info about the selected person (e.g. a JSON blob with notes, prior sightings). Initial state is a single "loading" row that swaps for content.

**Behavior**:
- Verified faces stream in via `EXTRA_REID_FACES` broadcasts and `EXTRA_REID_PERSON_JSON` for intel.
- DPAD scrolls through faces; CENTER opens the intel modal; BACK closes it.
- Toggling REID also toggles the running camera pipeline -- entering Night Vision automatically stops REID and vice versa.

**Designer notes**: this tab has the highest information density per pixel. The thumbnails are the only "imagery" allowed in the design system, so they need a careful frame treatment (border-as-selection, not border-as-decoration). The intel modal should feel like a reveal, not a popup.

---

### 6. MUSIC tab (dynamic -- present only while a phone is connected as A2DP source)

**Purpose**: now-playing display + transport controls for whatever the connected phone is playing.

**Layout**:
- Track text (title / artist / album, depending on what AVRCP exposes).
- Play/pause state glyph.
- `musicProgressBg` + `musicProgressFill` -- a thin progress bar that auto-advances from the last sampled position (`positionTs`-anchored).
- Skip/toggle is gated by a 1s cross-action cooldown to debounce flaps.

**Focus model**: `MUSIC_FOCUSED` -- DPAD = skip prev/next, CENTER = play/pause toggle.

**Designer notes**: the tab disappears the moment the phone disconnects, so there is no "no music" empty state to design. Track text can flap during AVRCP state changes -- design must tolerate transient empty strings without thrashing layout.

---

### 7. NIGHTVISION tab (dynamic)

**Purpose**: low-light camera passthrough rendered onto the waveguide.

**Layout**:
- Full-bleed `nightvisionPreview` ImageView (centerCrop), backed by `NightVisionPreview` + `NightVisionML` modules.
- A two-slider ML control overlay (driven by `nvSliderIndex`): `0 = exposure`, `1 = amplification`. `nvSliderLocked` toggles between "selecting which slider" and "adjusting that slider's value", swiped via DPAD with a 500ms debounce.
- No status bar visualizer here -- status area is hidden while this tab is active.

**Focus model**: `NIGHTVISION_FOCUSED` -- DPAD swipes to choose slider / change value, CENTER locks/unlocks.

**Designer notes**: the preview itself is the design. Slider chrome must be minimal -- a thin slider track + tick + numeric readout, all in luminance steps so it never competes with the image. Activating this tab automatically halts the REID pipeline; the transition needs to feel intentional.

---

### 8. TRANSLATE tab (dynamic)

**Purpose**: live translation display while in a foreign-language conversation.

**Layout**:
- `translationContainer` is a vertical LinearLayout (full bleed).
- A ScrollView holds a flowing translated-text TextView. The phone-side handles ASR + NLLB translation; the glasses just render the result line by line.

**Focus model**: `TRANSLATE_FOCUSED` -- DPAD scrolls the translation back-buffer; CENTER not used.

**Designer notes**: this is a "subtitle film" experience. Type sizing matters more than chrome. Treat it as a teleprompter cousin -- maximum readability, minimum decoration.

---

### 9. MAP tab (dynamic, navigation)

**Purpose**: hands-free walking directions.

**Layout (`mapContainer` is built programmatically)**:
- **Top step strip** (`mapStepStrip`): two-line label. Line 1 = current "flow" / step label, line 2 = direction (e.g. "Turn right onto X").
- **Middle**: `mapContentView` ImageView showing a static map snapshot with the route, takes remaining vertical space.
- **Pin button** (`mapPinButton`) for marking the destination / saving a pin.
- A `STEPS_MODAL` focus state shows the full step list as an overlay, scrollable.

**Focus model**: `MAP_FOCUSED` -- DPAD scrubs through steps (top strip updates), CENTER opens `STEPS_MODAL` for the full list, BACK closes.

**Designer notes**: the map raster is a borrowed image -- design here is mostly the step strip and the modal. Step text is the one place a designer can lean into typography hierarchy.

---

### 10. TELEPROMPTER tab (dynamic)

**Purpose**: full-screen reading aid. The wearer reads a script; text auto-scrolls.

**Layout**:
- Full-bleed `teleprompterContainer` FrameLayout.
- Inside: a ScrollView + TextView at 22sp `--glow` monospace, controlled by `TeleprompterController`.
- Word-by-word highlight via SpannableString + ForegroundColorSpan -- the active word is rendered brighter than its neighbours.
- States: `IDLE`, `RUNNING`, `PAUSED`. Speed default 3 px/tick @ ~30fps. Manual scroll pauses auto-scroll for 2s before resuming.

**Focus model**: `TELEPROMPTER_FOCUSED` -- CENTER toggles pause, DPAD_RIGHT speeds up, DPAD_LEFT slows down.

**Designer notes**: the only tab where 22sp is allowed. The "current word" highlight is what makes it feel alive -- this is where the system says "things glide and breathe" most literally.

---

### 11. MOUSE tab (dynamic)

**Purpose**: turn the glasses into a Bluetooth HID mouse for the connected phone (or PC). Head/touchpad gestures generate cursor moves.

**Layout**:
- `mouseContainer` LinearLayout.
- Pairing/connection status block.
- (Minimal -- the cursor is on the *target* device, not on the glasses.)
- Backed by `mouse/HeadTracker.kt` (50 Hz gyro) and `mouse/DpadInputHandler`.

**Focus model**: `MOUSE_FOCUSED` -- input is consumed for cursor control, no in-tab navigation.

**Designer notes**: this tab is intentionally near-empty. Design opportunity: a calm "mouse mode active" affordance + a connection indicator + a hint at how to exit. Do not put a fake cursor on the glasses display.

---

## Cross-cutting modals & states

These are not tabs but appear over them:

- `STOP_MODAL` -- asks for confirmation to cancel an active session (LISTENING / RESPONDING). Triggered by double-tap CENTER during a session.
- `STEPS_MODAL` -- full route step list (Map tab).
- `REID_INTEL_MODAL` -- full intel card on a recognised face.
- `CALL_INCOMING` / `CALL_ACTIVE` -- HFP call state, rendered via `CallOverlay`.
- Notification overlay -- ephemeral phone notification mirror.
- Telegram preview countdown -- "Sending in 2s..." cancellable send.

## What needs design love (suggested priorities)

1. **CHAT tab** -- most-used, most-watched. Bubble rhythm, status pulse, visualizer, "Thinking..." timer.
2. **REID tab** -- highest information density, has imagery (face thumbs) which most other tabs lack.
3. **TODO subtab pill + four list shapes** -- four distinct mental models in one frame, currently fairly uniform.
4. **TELEGRAM flow** -- 5+ sub-states, needs a coherent depth metaphor.
5. **MAP step strip** -- the one tab with a borrowed image; the rest is typography.
6. **TELEPROMPTER word highlight** -- best place to lean into "alive, not static".
7. **Status bar** -- shared across every tab; small wins compound.

---

# Attached: Glasses Client Design System (verbatim)

(See `system.md` in this directory. Reproduced here as a single bundle for the designer.)

```
# Glasses Client Design System

## Intent

Who: A person wearing AR glasses while moving through the real world. Their eyes serve two masters -- the physical environment and the data overlay. Every green pixel competes with reality behind it.

Task: Glance at AI responses, track conversation state, switch contexts. Hands-free, eyes-busy. Information must be absorbed in fractions of a second.

Feel: A whisper projected onto air. Information that materializes and dissolves like breath on cold glass. Not a screen bolted to your face -- a ghost of data hovering at the edge of awareness. Alive, not static.

## Hardware Constraints

Rokid AR Lite monochrome green micro-LED waveguide:
- Black (#000000) = pixels OFF = transparent see-through
- Any non-black pixel = green light emitted at that intensity
- No color. No gradients that blend to non-black (they glow). Only luminance variation within green channel.
- Alpha/transparency lights up the waveguide -- never use it
- Every View needs explicit android:background="#000000" or runtime equivalent
- focusable/focusHighlight must be disabled on all scrollable/focusable Views

## Luminance Palette

Hierarchy through brightness alone. Brighter = more important.

  --glow    #00FF00  Primary text, active indicators, focus
  --bright  #00DD00  Secondary interactive, user bubbles
  --mid     #00AA00  Body text, assistant content
  --dim     #007700  Metadata, timestamps, supporting info
  --ghost   #004400  Structural hints, inactive tab icons
  --trace   #002200  Ultra-subtle structure (hairline borders)
  --void    #000000  Background (transparent on waveguide)

## Typography

Monospace only -- this is a terminal projected onto glass.

  Status        9sp   Normal   --dim     Status bar text
  Body          14sp  Normal   --mid     Chat messages
  User          14sp  Normal   --bright  User's own messages
  Tool          11sp  Italic   --dim     Tool status indicators
  System        11sp  Normal   --ghost   System messages, metadata
  Teleprompter  22sp  Normal   --glow    Full-screen reading mode

## Layout

Edge-to-edge. The waveguide has no physical edges.
  - Debug text: absolute top
  - Status bar: directly below
  - Tab bar: absolute bottom (centered pill)
  - Content fills the remainder

Spacing base unit 4dp. Scale: micro 2 / xs 4 / sm 8 / md 12 / lg 16.

## Depth: borderless floating

No decorative borders. Structure from spacing + luminance only.
  - 0.5dp hairline (--trace) only between status/content/tab if spacing isn't enough
  - User bubbles: --trace fill, no stroke
  - Selected items: --glow border, rounded
  - Focused element: thicker --glow border (animated)

## Border radius

  Chat bubbles    12dp
  Tab pill        13dp
  Tool badges     8dp
  Chat list item  8dp
  Outer container removed

## Tab pill

  TAB_SLOT_DP=38, TAB_ICON_DP=13, TAB_BAR_COMPACT_DP=22, TAB_BAR_EXPANDED_DP=36
  Pill: --void fill, --glow 0.5dp border, 13dp radius
  Active: sliding --ghost capsule, 10dp radius, 3dp inset
  Inactive icon --ghost, active --glow, default selected = CHAT (idx 1)
  Focused pill border thickens to 1.5dp (150ms)
  Highlight slides 200ms ease-out
  >5 tabs: time/battery move to top, pill stays bottom
  All icons filled style

  Todo subtab pill: same width, icons (checklist/bookmark/lightning/alarm), --glow 0.4dp border

  Battery charging: 16dp lightning bolt overlay on battery body when CHARGING/FULL

## Icons

Use icons everywhere to reduce text -- icons are the native language of a HUD.

## Animation

Everything animates. Static = broken hologram.
  Micro 150ms / Standard 250ms / Emphasis 400ms / Slow breathe 1500ms
  Message materialization: alpha 0->1 + 8dp slide up, 250ms
  Status crossfade: 150ms each direction
  Thinking pulse: --mid <-> --glow 1500ms cycle
  Tab pill slide: 200ms ease-out + icon color crossfade
  Chat list selection: border 0->1dp 150ms
  Focus border: 0.5dp -> 1.5dp 150ms
  Center-message selection: 0.5dp ghost border tracks scroll
  View transitions: 150ms fade out / 200ms fade in + 4dp slide
  Teleprompter enter/exit: 200/300ms fade
  Tool badge appear: scale 0.8->1.0 + fade 200ms
  Scroll indicator: 1dp glow bar, height = visible ratio, fades 1s after scroll stop

## Bubbles

User: --trace fill, --bright text, right, 12dp radius, 8/6 padding, no stroke
Assistant: transparent, --mid, left, 8/6 padding
Tool: transparent + small icon prefix, --dim italic 11sp, 8dp radius if any
System/meta: centered, --ghost, 11sp, no container

## Chat list items

No border by default
Title --mid 13sp, subtitle --dim 11sp
Selected: --ghost border 0->1dp 150ms, 8dp radius
Active conversation: title --glow

## Status bar

Icon + short text, 11sp mono, --dim default
Listening: pulsing icon, --glow text
Thinking: pulse cycle
No background fill, no border

## Debug status

10sp mono, --ghost. Hide in production.

## Don'ts

  No solid borders heavier than 0.5dp
  No full --glow borders -- they overpower content
  No instant visibility changes -- always animate
  No alpha on backgrounds -- lights up the waveguide
  No purely decorative elements
  No container borders without interactive meaning
  No shadows
```
