# Merge consecutive same-sender notifications (visual overlay) — implementation design

RESEARCH ONLY findings + plan. All line refs as of this commit.

## 1. Canonical sender key (reuse from TTS-continue)
The TTS-continue "same sender => no `X wrote:` prefix" decision is made PHONE-SIDE in
`NotificationQueue.processNext()`:
- `phone/.../notification/NotificationQueue.kt:222` `val includeSender = lastAnnouncedSender != notif.sender`
- `:223-224` `ttsSender = if (includeSender) "" else ...; lastAnnouncedSender = notif.sender`
- `lastAnnouncedSender` decl `:60`; reset after `SENDER_RESET_MS = 10_000L` idle (`:70,:210-211`).

The identity field is `QueuedNotification.sender` (`:24`), which is the Telegram
`android.title` string captured in `TelegramNotificationListener.kt:121`
(`extras.getCharSequence("android.title")`). For GROUP chats `chat` is the group name
(`:154`); `sender` stays the person. So the canonical key is the **`sender` display name string**.

Important seam note: TTS-continue lives on the PHONE; the visual overlay lives on the
GLASSES. The glasses receive `(notifId, sender, text, chat, repliable)` via `CH_NOTIFICATION`
(`BtProtocol.kt:129`). The glasses-side merge must key on the same `sender` string it
already receives in `onNotification(...)`. This stays consistent with TTS-continue because
both compare the identical Telegram title string. No new field needed.

## 2. Data model
- BT payload: `CH_NOTIFICATION` args `[notifId, sender, text, chat, repliable]` (`BtProtocol.kt:129,132`).
- Overlay data class: `NotificationOverlay.NotificationData(notifId, sender, text, chat, repliable)`
  (`NotificationOverlay.kt:44-50`). Currently shown item = `current: NotificationData?`
  (`:66`), exposed read-only via `currentData` (`:78`).
- Rendered text = `messageLabel` (`:96`, set at `:445`), `maxLines = 2` + END ellipsize (`:253-255`).
- Sender rendered in `senderLabel` (`:93`, set `:440-444`), 1 line ellipsized.

## 3. Overlay queue + current item (recommended new API)
Current on-screen item is `current` (set in `showNext():437`). Its text is set at `:445`.
Dismiss timer: `dismissRunnable` (`:130`) posted via `handler.postDelayed(dismissRunnable, currentDismissDelayMs)`
(`:497`). `currentDismissDelayMs` (`:128-129`) = `repliableDurationMs(12000L)` if repliable
else `displayDurationMs` = `GlassesConfig.notificationDurationMs` (5000L default, `:126`).
`frozen` (`:67`) gates the timer; set by `freezeDismiss()/unfreezeDismiss()` (`:809-823`).

ADD a new method to `NotificationOverlay`:
```
/** Returns true if it merged into the live item (same sender, not folded/empty). */
fun appendToCurrent(sender: String, extraText: String): Boolean {
    // MUST run on main thread (handler.post) and read `current` there.
    val cur = current ?: return false
    if (!isShowing) return false
    if (cur.sender != sender) return false
    val merged = cur.text + "\n" + extraText
    current = cur.copy(text = merged)
    messageLabel.text = merged                    // re-render
    if (!frozen) {                                 // reply owns timer when frozen
        handler.removeCallbacks(dismissRunnable)
        handler.postDelayed(dismissRunnable, currentDismissDelayMs)  // restart FULL duration
    }
    return true
}
```
Because `ConcurrentLinkedQueue` access + `current` are only safe on the overlay handler
thread, wrap the body in `handler.post {}` and return the merge decision via a different
path (see §4 — do the decision in the service against `currentData`, call append, get bool).

## 4. Where the merge decision lives — RECOMMEND seam (A): service `onNotification`
Insert in `ListenerService.onNotification(...)` (`ListenerService.kt:6925`) AFTER the
suppress/dedup guards (after `:6957`) and BEFORE `wakeScreenForNotification` + `notificationOverlay.show(...)` (`:6965-6966`):

```
val cur = notificationOverlay.currentData
if (cur != null && cur.sender == sender) {
    val merged = notificationOverlay.appendToCurrent(sender, text)
    if (merged) {
        // absorbed: 2nd notifId never enters the overlay queue, so its DONE
        // must be acked NOW or the phone NotificationQueue stalls (see below).
        notifHandler.removeCallbacks(notifNoTtsTimeout)
        notifHandler.removeCallbacks(notifLockScreenRunnable)
        wakeScreenForNotification(cur.repliable)   // re-arm timed screen lock (§5)
        // DONE-ACK for absorbed id: TTS still flows for it (own notifId);
        // let the existing TTS/overlay latch handle it BUT the overlay will
        // never call onItemDismissed for this id. So mark overlay-done now:
        synchronized(notifLatchLock) { notifOverlayDoneIds.add(notifId) }
        checkNotifComplete(notifId)
        return
    }
}
```

### DONE-ack flow (critical)
Each notifId needs BOTH `notifTtsDoneIds` AND `notifOverlayDoneIds` before
`checkNotifComplete()` sends `CH_NOTIFICATION_DONE` (`ListenerService.kt:6862-6879`).
Normally overlay-done fires from `onItemDismissed` (`:2461-2468`). A merged 2nd notifId is
NEVER enqueued, so `onItemDismissed` will never fire for it → must add it to
`notifOverlayDoneIds` manually (shown above). TTS for the 2nd id still arrives via
`onNotificationTtsAudio` (`:6981`) and completes its own `notifTtsDoneIds` normally, so
`checkNotifComplete(notifId)` then fully closes it and acks the phone. Without this ack the
phone `NotificationQueue` (`onGlassesDone`, NotificationQueue.kt:159) never advances.

### Reply-in-progress interaction
If a reply is active on the current item, `frozen == true` (set by `notifReplyStartReceiver`
→ `freezeDismiss()` `:7617`). `appendToCurrent` MUST append text but NOT touch the timer
when `frozen` (already guarded above) — the reply owns the timer/screen. Also DO NOT call
`wakeScreenForNotification` in that case: `wakeScreenForNotification` already early-returns
when `notifReplyHoldingScreen` (`:6901-6904`), so calling it is safe/no-op. Leave the reply
untouched; just merge the text.

## 5. Timer + screen-lock restart
- Timer restart: `notifHandler` is wrong — the OVERLAY uses its own `handler` (`:62`).
  Restart inside the overlay: `handler.removeCallbacks(dismissRunnable)` +
  `handler.postDelayed(dismissRunnable, currentDismissDelayMs)` (matches §3). This gives the
  user's 5s example: append at t=2s restarts 5s → on-screen 7s total. Correct.
- Screen lock: normal notifs hold a TIMED `notifScreenLock` sized to the window
  (`wakeScreenForNotification`, `:6888-6923`, `windowMs = notificationDurationMs` non-repliable).
  A merge that restarts the 5s display timer MUST re-arm this lock or the screen can sleep
  before the extended window ends. Re-calling `wakeScreenForNotification(cur.repliable)`
  (as in §4) releases+re-acquires a fresh timed lock (`:6907-6917`) — exactly right. It
  early-returns off-head (`:6893`) and during reply (`:6901`), both desired.

## 6. Text append format
`messageLabel` is `maxLines = 2`, END-ellipsized (`:253-255`). A `\n` join shows msg1 on
line 1, msg2 on line 2 — fits exactly 2 messages. For a 3rd+ merge the oldest line
ellipsizes away (acceptable; latest stays visible). RECOMMEND `"\n"` join (one message per
line). If more than 2 lines is desired, bump `messageLabel.maxLines` (e.g. 3-4) at `:254`
— but note the box has no scroll, so keep it small (3). A space-join `"  "` would let END
ellipsize hide msg2 entirely on long msg1; `\n` is safer. Keep `data class` immutable via
`.copy(text=...)`.

## Gotchas summary
1. Merge decision against `currentData` (live item) ONLY — queued-but-not-shown items are
   NOT merge targets (user spec: "currently displayed").
2. Absorbed 2nd notifId: manually push to `notifOverlayDoneIds` + `checkNotifComplete` or
   phone queue stalls.
3. Frozen (reply active): append text, do NOT restart timer or re-arm timed screen lock.
4. `messageLabel.maxLines=2` truncates 3rd+ message — `\n` join, optionally raise to 3.
5. All overlay mutations on the overlay `handler` thread; `current`/queue not thread-safe
   otherwise.
6. Sender key = exact `sender` string (same as TTS-continue `lastAnnouncedSender`), no
   normalization — keep both identical.
