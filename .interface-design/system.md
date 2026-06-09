# Glasses Client Design System

## Intent

**Who:** A person wearing AR glasses while moving through the real world. Their eyes serve two masters -- the physical environment and the data overlay. Every green pixel competes with reality behind it.

**Task:** Glance at AI responses, track conversation state, switch contexts. Hands-free, eyes-busy. Information must be absorbed in fractions of a second.

**Feel:** A whisper projected onto air. Information that materializes and dissolves like breath on cold glass. Not a screen bolted to your face -- a ghost of data hovering at the edge of awareness. Alive, not static.

## Hardware Constraints

Rokid AR Lite monochrome green micro-LED waveguide:
- Resolution: 480x640 @ 240dpi (density bucket `hdpi`, so 1dp = 1.5px). Right eye only (monocular). Up to 144 Hz.
- Black (#000000) = pixels OFF = transparent see-through
- Any non-black pixel = green light emitted at that intensity
- No color. No gradients that blend to non-black (they glow). Only luminance variation within green channel.
- Alpha/transparency lights up the waveguide -- never use it
- Every View needs explicit `android:background="#000000"` or runtime equivalent
- focusable/focusHighlight must be disabled on all scrollable/focusable Views

## Luminance Palette

Hierarchy through brightness alone. Brighter = more important.

| Token             | Hex       | Use                                      |
|-------------------|-----------|------------------------------------------|
| `--glow`          | `#00FF00` | Primary text, active indicators, focus    |
| `--bright`        | `#00DD00` | Secondary interactive, user bubbles       |
| `--mid`           | `#00AA00` | Body text, assistant content              |
| `--dim`           | `#007700` | Metadata, timestamps, supporting info     |
| `--ghost`         | `#004400` | Structural hints, inactive tab icons      |
| `--trace`         | `#002200` | Ultra-subtle structure (hairline borders) |
| `--void`          | `#000000` | Background (transparent on waveguide)     |

In Kotlin constants:
```kotlin
object Lum {
    const val GLOW    = 0xFF00FF00.toInt()
    const val BRIGHT  = 0xFF00DD00.toInt()
    const val MID     = 0xFF00AA00.toInt()
    const val DIM     = 0xFF007700.toInt()
    const val GHOST   = 0xFF004400.toInt()
    const val TRACE   = 0xFF002200.toInt()
    const val VOID    = 0xFF000000.toInt()
}
```

## Typography

Monospace only -- this is a terminal projected onto glass. Consistent character width aids glanceability.

| Level      | Size   | Weight       | Color      | Use                          |
|------------|--------|--------------|------------|------------------------------|
| Status     | 9sp    | Normal       | `--dim`    | Status bar text (time, battery) |
| Body       | 14sp   | Normal       | `--mid`    | Chat messages                |
| User       | 14sp   | Normal       | `--bright` | User's own messages          |
| Tool       | 11sp   | Italic       | `--dim`    | Tool status indicators       |
| System     | 11sp   | Normal       | `--ghost`  | System messages, metadata    |
| Teleprompter | 22sp | Normal       | `--glow`   | Full-screen reading mode     |

## Layout Strategy: Edge-to-Edge

The waveguide lens has no physical edges -- it is a floating projection. No padding from screen borders. All content uses 100% of the display width and height.

- Debug text: absolute top of screen
- Status bar: directly below debug
- Tab bar: absolute bottom of screen
- Content area: fills all remaining space between status and tabs

## Spacing

Base unit: 4dp. All spacing is multiples of 4. No screen-edge padding -- the lens is edgeless.

| Scale   | Value | Use                                    |
|---------|-------|----------------------------------------|
| micro   | 2dp   | Icon-to-label gap, indicator margins   |
| xs      | 4dp   | Inline padding, tight gaps             |
| sm      | 8dp   | Component internal padding             |
| md      | 12dp  | Section gaps, card padding             |
| lg      | 16dp  | Major section separation               |

## Depth Strategy: Borderless Floating

No borders for decoration. Structure comes from spacing and luminance difference alone.

**Rules:**
- No container borders by default. Content floats on void.
- The only structural line is a single 0.5dp hairline (`--trace`) separating the status area from content, and content from tab bar -- and ONLY if spacing alone doesn't create enough separation.
- Chat bubbles: no border stroke. Use a barely-visible rounded background fill (`--trace` or slightly above) to create a whisper of containment.
- Selected items: `--glow` border, rounded. Full green to match active icon brightness.
- Focus indication: border width change. Focused element gets thicker `--glow` border. Unfocused elements keep 0.5dp default.
- The outer container border (currently 2dp `--glow`) is removed entirely. The chat area floats.

## Border Radius

Everything rounded. Sharp corners feel mechanical; rounded corners feel projected, organic.

| Element         | Radius |
|-----------------|--------|
| Chat bubbles    | 12dp   |
| Tab pill        | 13dp   |
| Tool badges     | 8dp    |
| Chat list items | 8dp    |
| Outer container | removed (no border = no radius needed) |

## Tab Bar: Pill Design

Compact pill at screen bottom. Constants defined in `MainActivity.companion`:
- `TAB_SLOT_DP = 38` -- width per tab slot
- `TAB_ICON_DP = 13` -- icon size inside each slot
- `TAB_BAR_COMPACT_DP = 22` -- tab bar height with <=5 tabs
- `TAB_BAR_EXPANDED_DP = 36` -- tab bar height with >5 tabs (status indicators move to top row)
- `TAB_OVERFLOW_THRESHOLD = 5` -- when to expand

- Pill background: `--void` fill, `--glow` 0.5dp border, 13dp radius
- Active tab: sliding highlight capsule (`--ghost` fill, 10dp radius, 3dp inset via InsetDrawable)
- Inactive icon: `--ghost` color
- Active icon: `--glow` color
- Default selected tab: CHAT (index 1)
- When tab bar is focused: pill border thickens to 1.5dp (animated 150ms)
- The highlight capsule slides smoothly between positions on tab switch (200ms ease-out)
- When >5 tabs: tab bar expands, time/battery move to top-left/top-right, pill stays at bottom-center
- All icons are filled style (not outline)

### Todo Subtab Pill

Same width as main pill (TAB_SLOT_DP * 4), centered. Uses icons instead of text labels.

- Pill background: `--void` fill, `--glow` 0.4dp border, 13dp radius
- Icons: checklist (tasks), bookmark (saved), lightning (jobs), alarm (alarms) -- all 13dp, filled
- Active icon: `--glow` tint
- Inactive icon: `--ghost` tint
- Capsule: `--ghost` fill, 10dp radius, 3dp inset via InsetDrawable
- Wrapper margins: 3dp top/bottom
- No separator line between content and pill

### Battery Charging Indicator

- Lightning bolt icon (ic_charging.xml) overlaid on battery body via tabBar FrameLayout
- 16dp ImageView, positioned centered over battery body after layout
- Green fill (#00FF00) with black outline (strokeWidth=300 in 10240-unit viewport)
- Shape from user-provided SVG, sharp tips (no rounding)
- Shown when BatteryManager.EXTRA_STATUS == CHARGING or FULL
- tabBar and mainContentLayout have clipChildren=false to allow overflow

## Icons

Use everywhere possible to reduce text. Icons are the native language of a HUD.

| Context              | Icon                        | Color when active | Color when inactive |
|----------------------|-----------------------------|-------------------|---------------------|
| Chat tab             | Chat bubble (existing)      | `--glow`          | `--ghost`           |
| Chat list tab        | List icon (existing)        | `--glow`          | `--ghost`           |
| Status: Ready        | Small circle / dot          | `--dim`           | --                  |
| Status: Listening    | Microphone / sound wave     | `--glow`          | --                  |
| Status: Thinking     | Animated dots / pulse       | `--bright`        | --                  |
| Tool usage           | Wrench / gear (small)       | `--dim`           | --                  |
| User message         | No icon (alignment is enough) | --              | --                  |
| Assistant message    | Small spark / diamond       | `--dim`           | --                  |

## Animation System

Everything animates. Static changes feel broken on a HUD -- like a broken hologram.

### Timing

| Category       | Duration | Easing              |
|----------------|----------|----------------------|
| Micro          | 150ms    | DecelerateInterpolator |
| Standard       | 250ms    | DecelerateInterpolator |
| Emphasis       | 400ms    | DecelerateInterpolator |
| Slow breathe   | 1500ms   | AccelerateDecelerateInterpolator |

### Catalog

**Message materialization:** New messages fade in (alpha 0 -> 1) over 250ms while sliding up 8dp from below. Creates the feeling of information rising into the field of view.

**Status crossfade:** Status text changes crossfade -- old text fades out (150ms), new text fades in (150ms). Never instant-swap.

**Thinking pulse:** During "Thinking..." state, the status text gently pulses brightness between `--mid` and `--glow` on a 1500ms cycle. Conveys life, not static waiting.

**Tab pill slide:** The active highlight capsule translates horizontally (200ms ease-out) to the selected tab position. Icons crossfade color simultaneously.

**Chat list selection:** Selected item border width animates from 0 to 1dp (150ms). Previous selection border removed.

**Focus border:** When an element gains focus, its border width animates from 0.5dp to 1.5dp (150ms). Losing focus reverses.

**Center message selection:** In CHAT_FOCUSED state, the message closest to the viewport center gets a 0.5dp `--ghost` border. Tracks scroll position in real-time.

**View transitions:** Chat <-> ChatList: outgoing view fades out (150ms), incoming view fades in (200ms) with 4dp vertical slide. No harsh cuts.

**Teleprompter enter/exit:** Content area fades out (200ms), teleprompter text fades in from 0 opacity (300ms). Exit reverses. The text appears to materialize from void.

**Tool badge appear:** Tool status messages scale from 0.8 -> 1.0 and fade in simultaneously (200ms). Feels like a brief system notification blinking into existence.

**Scroll indicator:** A thin (1dp) glow bar at the right edge, height proportional to visible content ratio, fades in when scrolling starts, fades out 1s after scroll stops.

## Chat Bubbles

Minimalist. No heavy borders.

**User messages:**
- Background: `--trace` fill (barely visible rounded shape)
- Text: `--bright`
- Aligned right
- Radius: 12dp
- Padding: 8dp horizontal, 6dp vertical
- No border stroke

**Assistant messages:**
- Background: transparent (no fill, text floats on void)
- Text: `--mid`
- Aligned left
- Padding: 8dp horizontal, 6dp vertical
- No border, no fill

**Tool messages:**
- Background: transparent
- Small icon prefix (4dp gap)
- Text: `--dim`, italic, 11sp
- Radius: 8dp (if any container needed)

**System/Metadata:**
- Centered
- Text: `--ghost`
- 11sp
- No container at all

## Chat List Items

- No border by default
- Title: `--mid`, 13sp
- Subtitle: `--dim`, 11sp
- Selected: `--ghost` border animates width from 0 to 1dp (150ms), 8dp radius
- Active conversation: title uses `--glow` instead of `--mid`

## Status Bar

- Minimal: icon + short text
- 11sp monospace, `--dim` by default
- During "Listening": icon animates (pulse), text becomes `--glow`
- During "Thinking": animated dots or pulse cycle
- No background fill, no border. Floats at top.

## Debug Status

- 10sp monospace, `--ghost`
- Only visible during development. Consider hiding in production.

## What NOT to Do

- No solid borders heavier than 0.5dp
- No full-brightness (#00FF00) borders -- they overpower content
- No instant visibility changes (always animate)
- No alpha/transparency on backgrounds (lights up waveguide)
- No decorative elements -- if it doesn't convey information, remove it
- No container borders unless the container has interactive meaning (selected state, pill)
- No shadows (meaningless on monochrome waveguide)
