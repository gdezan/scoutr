# Keep Scoutr monitoring as a time-bounded foreground session

Scoutr will keep its app-owned ntfy monitor as an explicitly time-bounded Android foreground session. It will not present the current `dataSync` service as durable all-day background monitoring on Android 15 and newer.

## Context

The monitor polls a self-hosted ntfy topic so blocked and done agent events can produce Scoutr notifications, deep links, and inline replies. Android 15 limits an app's `dataSync` foreground services to six background hours in a 24-hour period. A service that ignores `Service.onTimeout` can be terminated with a fatal exception, while `START_STICKY` can silently recreate a session the user did not actively start.

The official ntfy Android client remains a possible future background-delivery owner, but adopting it would add an installation dependency and requires validation of its broadcast contract, notification ownership, and reply/deep-link integration. WorkManager is not suitable for real-time monitoring because periodic work is inexact and has a minimum interval.

## Decision

- Keep `ScoutrMonitorService` as the app-owned ntfy poller for now.
- Return `START_NOT_STICKY`; an expired or system-stopped session must not silently restart.
- Handle both foreground-service timeout callbacks by cancelling the poll, removing the foreground notification, stopping the service, and clearing the monitoring opt-in.
- Explain the six-hour Android 15+ limit in Settings and in the foreground-service notification.
- Do not claim durable all-day monitoring. Reconsider ownership after validating the official ntfy client or another self-hosted background-delivery path.

## Consequences

Users get notification delivery during an opt-in session, with an honest limit and no timeout crash or automatic restart after the limit. On Android 15+, the user must enable monitoring again after the six-hour session ends. The existing ntfy cursor remains resume-safe, but this decision does not solve background delivery beyond the platform's foreground-service quota.
