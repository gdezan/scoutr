# Deliver push as a contentless FCM ping the app resolves over the tailnet

Scoutr's push runs on Firebase Cloud Messaging, and every message carries only `kind` and `paneId`. Nothing identifying an agent, a workspace, or a session ever transits Google. The app wakes on the ping and fetches the detail from the bridge over the tailnet.

Supersedes ADR 0004, which is deleted along with the ntfy subsystem it described.

## Context

Push almost never arrived, and when it did it said nothing useful. Three independent defects, all verified in the code that shipped:

- **The delivery gate was unsatisfiable.** `ScoutrMonitorService.pollOnce` notified only when `message.paneId != null`, but `paneId` was published as a custom JSON field and ntfy drops unknown fields. Every polled message advanced the cursor and was then discarded. When the service ran at all, it showed nothing.
- **Delivery only existed inside an opt-in, time-boxed window.** The poller lived in a `dataSync` foreground service, which Android 15 terminates after six background hours in 24. It returned `START_NOT_STICKY`, cleared its opt-in in `onDestroy`, and had no boot receiver. Outside that window there was no delivery path at all — the honest limitation ADR 0004 accepted.
- **Presentation was unowned.** Two notification builders — one in the monitor service, one in `AppContainer` — created the same channel with different content, both keyed every event to a fresh id, so notifications stacked forever and never cleared.

The first two are not bugs to fix in place: an app-owned poller cannot survive Doze, OEM task killers, or the platform's foreground-service quota, and no amount of care inside that service changes it. Only a push transport the system itself wakes does.

## Decision

- **Transport.** The bridge sends to FCM HTTP v1, authenticated by a service-account key at `~/.config/scoutr/fcm-service-account.json` (mode `0600`, never in git).
- **Data-only, always.** The payload is `{"data":{"kind":…,"paneId":…},"android":{…}}` with no `notification` block, ever. This is load-bearing twice: no notification content transits Google, and Android guarantees `onMessageReceived` fires even when the app is backgrounded — which a message carrying a `notification` block does not.
- **Priority.** `blocked` goes out `high` with a 900s TTL; `resolve` goes `normal` with 3600s. An alert nobody saw for fifteen minutes is noise, not news.
- **Edge-triggered publishing.** `FcmPublisher` tracks which panes are blocked and sends only on transitions in and out of that state. This replaces the old 60s/pane throttle, which could have swallowed the resolve that clears the phone.
- **The app resolves identity itself.** On a `blocked` ping it fetches `GET /api/agents`, finds the pane, and posts the agent's name and workspace. On failure it retries twice, then posts a degraded "An agent needs you".
- **One presenter, one channel, one slot per pane.** `NotificationPresenter` is the sole owner: channel `needs_you`, slot `paneId.hashCode()`, group summary from two up, Reply and Mute actions, and content intents built only through `resolveNotificationLink`.
- **The bridge clears what it posted.** A `resolve` ping cancels the slot; foregrounding the app reconciles against `/api/agents` as the backstop.

## Consequences

A blocked agent reaches the phone whether or not Scoutr is running, and the notification clears itself when the agent unblocks. There is no foreground service, no monitoring toggle, and no six-hour window.

Two costs are accepted deliberately:

- **Google Play Services becomes a hard dependency for notifications.** A de-Googled device gets no push at all. This was weighed against a `remoteMessaging` foreground service holding the bridge's `/ws` feed — viable, since Android 15 times out only `dataSync` and `mediaProcessing` — and rejected because it still loses to Doze and OEM task killers, where FCM does not.
- **With a single high-importance channel, the degraded notification buzzes as loudly as a real alert.** A second, quieter channel would fix that and was rejected: two channels means two user-visible toggles for what is one event, and the degraded case is a network failure, not a lesser event.

The bridge gained exactly one runtime dependency, `google-auth-library`, to mint the OAuth2 token. `firebase-admin` pulls a large tree for one HTTP call; hand-rolling the RS256 JWT exchange would have meant owning ~40 lines of security-sensitive code.

Setup is no longer zero-config: a human must create a Firebase project, drop `google-services.json` into `android/app/`, and point `fcmServiceAccountPath` at the service-account key. Without it the bridge logs one warning at startup, `health.push.fcm` reports `false`, and everything else behaves exactly as before.
