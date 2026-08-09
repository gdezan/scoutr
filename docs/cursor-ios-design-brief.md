# Cursor for iOS — design and UX brief

**Research basis:** official Cursor docs, product page, launch post, changelog, and Apple App Store metadata. This brief separates **Fact** (directly documented) from **Inference** (a design recommendation based on those facts). It describes the public iPhone/iPad product, not an imagined mobile IDE.

## 1. Product summary

**Fact.** Cursor for iOS is a native app for starting and directing agents running in Cursor's cloud or on a user's computer. Agents started on mobile also appear in the desktop Agents window and at `cursor.com/agents`; the app uses the same backend. [Official mobile docs](https://cursor.com/docs/cloud-agent/mobile)

**Fact.** The core loop is: choose a repository and branch, send a task, follow the agent, inspect generated artifacts and code changes, send follow-ups, and review or merge the resulting pull request. Cloud agents run in isolated development environments, install dependencies, run tests, and verify work. [App Store](https://apps.apple.com/us/app/cursor/id6767085653) · [Mobile docs](https://cursor.com/docs/cloud-agent/mobile)

**Fact.** The app is a directing/reviewing surface, not an IDE: it has no editor, terminal, or file browser. Changed files are exposed through a diff view. [Mobile help](https://cursor.com/help/ai-features/mobile-app)

**Design implication (Inference).** Optimize for supervision, confidence, and fast decisions—not code authoring. Every screen should answer one of three questions: *What is running? What needs my input? Is this safe to ship?*

## 2. Visual language

**Fact.** The public mobile experience is presented as a focused agent/review product with demos, screenshots, logs, diffs, summaries, and PR states rather than an editor canvas. [Cursor Mobile](https://cursor.com/mobile) · [Launch post](https://cursor.com/blog/ios-mobile-app)

**Fact, broader-brand reference.** An independent analysis of Cursor's desktop language describes near-black surfaces, off-white type, restrained typography, hard-edged depth, and one electric-blue AI accent. It is not an official iOS design specification, so use it as a north-star rather than proof of exact mobile tokens. [Curio analysis](https://designbycurio.com/learn/cursor-ide)

**Recommendation (Inference).** Carry that semantic restraint into iOS:

- Use a dark neutral canvas, elevated charcoal cards, off-white primary text, and dim gray secondary text.
- Reserve the brand blue for AI-owned states and actions: agent activity, composer focus, active run, and AI-generated highlights. Use native/system colors for success, warning, failure, and destructive actions so status is not encoded by blue alone.
- Use native iOS typography and Dynamic Type; use monospace only for paths, commands, commits, and diff content.
- Prefer 1 px dividers, tonal elevation, and compact radii over decorative gradients or heavy shadows. Keep cards calm so a running agent's state is the visual anchor.
- Make touch targets at least 44 pt, preserve strong contrast, and support Reduce Motion, VoiceOver, Dynamic Type, and color-vision differences.

## 3. Navigation and information architecture

**Fact.** The app has an Inbox for organization; it exposes work in progress, work needing attention, and PRs in review. The profile entry is in the upper-left area, and the repository picker depends on source-control integrations already configured on the web. [iPad changelog](https://cursor.com/changelog/ipad) · [Mobile help](https://cursor.com/help/ai-features/mobile-app)

**Fact.** iPad keeps chats in a sidebar, supports several agents at once, opens a review beside a chat, and gives diffs full width. [Mobile docs](https://cursor.com/docs/cloud-agent/mobile)

**Recommendation (Inference).** Use a stack-based flow with these stable destinations:

1. **Inbox:** filterable feed of agent cards grouped by attention state.
2. **Agent detail:** chat stream, activity, artifacts, and follow-up composer.
3. **Review:** PR summary, files, commits, checks, comments, approvals, and merge actions.
4. **Profile/settings:** account, team, notification and Live Activity permissions, privacy/setup links.

On iPhone, push detail screens from the Inbox. On iPad, make Inbox/chat the sidebar and show detail/review in the primary column; allow review and chat to coexist without losing the current agent.

## 4. Chat and agent activity

**Fact.** Users can watch a live chat stream, send follow-ups while an agent runs, tap subagent cards to read child transcripts, use slash commands/skills/automations, attach files or images, use Design Mode markup, and dictate instructions with voice input. [Mobile docs](https://cursor.com/docs/cloud-agent/mobile) · [Launch post](https://cursor.com/blog/ios-mobile-app)

**Recommendation (Inference).** Treat chat as an event stream, not a generic messaging screen:

- Pin repository, branch, run state, selected model, and machine/source near the header.
- Render user messages, agent reasoning/output, tool activity, subagent cards, artifacts, and system events as distinct event types.
- Collapse repetitive tool output by default; expose a tap-through detail view and keep the latest meaningful event visible.
- Keep the composer available at the bottom while running. Show attachment, markup, voice, slash-command, and send controls as progressive disclosure rather than a crowded toolbar.
- Label every action that can change code or PR state with its scope: repository, branch, files, and proposed effect.

## 5. Motion and system feedback

**Fact.** Live Activities show up to eight running agents on the Lock Screen and Dynamic Island. Push notifications report completed turns, requests for input, or work ready for review. Streaming pauses in the background and catches up on return; the app is cache-first and syncs when connectivity returns. [Mobile docs](https://cursor.com/docs/cloud-agent/mobile) · [Mobile help](https://cursor.com/help/ai-features/mobile-app)

**Recommendation (Inference).** Motion should communicate continuity, not decoration:

- Use a restrained active indicator on the agent card and a determinate phase label when known: starting, working, verifying, awaiting input, ready for review, failed, or merged.
- Animate new stream events with short, non-blocking insertion; never make the user wait for animation to read the latest state.
- On reconnect, show “caught up” or an event timestamp rather than replaying a long animation sequence.
- Mirror the same state vocabulary across Inbox, detail, push notifications, and Live Activities.
- Respect Reduce Motion and provide an accessible textual state for every animated indicator. No official source publishes timing values; validate durations with usability testing.

## 6. Onboarding and empty states

**Fact.** Official setup is: sign in, choose a repository, choose a branch, and start directing an agent. SSO is supported. The repository list is empty until GitHub/GitLab or another source-control connection is configured on the web. [Mobile docs](https://cursor.com/docs/cloud-agent/mobile) · [Mobile help](https://cursor.com/help/ai-features/mobile-app)

**Fact.** Cloud Agents require an eligible paid plan and cloud data storage. When needed, Cursor prompts the user to switch to Privacy Mode. [Mobile docs](https://cursor.com/docs/cloud-agent/mobile)

**Recommendation (Inference).** Make setup explicit and recoverable:

- **First run:** sign in → explain “direct and review agents, not edit files” → choose team/repository/branch → show a safe example task → request notification and Live Activity permissions at the point of value.
- **No repositories:** state “Connect GitHub or GitLab on the web,” provide a deep link, then offer pull-to-refresh; do not show a blank picker with no explanation.
- **No agents:** show one primary CTA, “Start an agent,” and three examples: fix a bug, investigate CI, review a PR.
- **No connection:** render cached cards with timestamps and a clear “offline / retrying” state; preserve drafts locally.
- **Plan/privacy gate:** explain why the capability is unavailable, what data setting is required, and the next action. Never make a disabled “Start” button the only explanation.

## 7. Concrete patterns to implement

1. **Attention-first Inbox card** — Show repository/branch, task title, live state, last event time, short summary, and an attention badge. Put “Open” and “Send follow-up” in predictable positions. Fact basis: Inbox and mobile-page agent cards. [Changelog](https://cursor.com/changelog/ipad) · [Mobile](https://cursor.com/mobile)
2. **Run-state header** — Keep agent source (cloud or computer), repository, branch, model, and current phase visible while scrolling. This reduces uncertainty when several runs look similar. Inference from the documented cloud/local split. [Mobile docs](https://cursor.com/docs/cloud-agent/mobile)
3. **Streaming event timeline** — Use typed rows for assistant output, tool calls, verification, system notices, and subagents; collapse noisy details and retain timestamps. Fact basis: live chat and child transcripts. [Mobile docs](https://cursor.com/docs/cloud-agent/mobile)
4. **Persistent follow-up composer** — Let users send a follow-up to a running agent without leaving the stream; support text, voice, slash commands, attachments, and markup. Fact basis: documented follow-ups, voice, commands, and Design Mode. [Mobile docs](https://cursor.com/docs/cloud-agent/mobile)
5. **Subagent drill-down card** — Show child-agent title, state, and latest result; tap to open its transcript in a modal or nested screen, with a clear return path to the parent run. [Mobile docs](https://cursor.com/docs/cloud-agent/mobile)
6. **Artifact verification strip** — Present screenshots, videos, and logs as horizontally browsable artifacts above the final summary, with full-screen viewing and timestamps. Fact basis: agents return these artifacts. [App Store](https://apps.apple.com/us/app/cursor/id6767085653) · [Launch post](https://cursor.com/blog/ios-mobile-app)
7. **Point-and-draw markup** — Attach an image, tap to pin a comment, or draw with Apple Pencil; show the annotation as an input attachment before sending. Fact basis: Design Mode and iPad markup. [Mobile docs](https://cursor.com/docs/cloud-agent/mobile) · [iPad changelog](https://cursor.com/changelog/ipad)
8. **Mobile PR review surface** — Structure review as summary → commits/files → diff → comments/checks/approvals. Keep “ask agent to resolve” near comments and keep merge controls separate from browsing. Fact basis: full PR review is documented. [iPad changelog](https://cursor.com/changelog/ipad)
9. **Guarded merge action** — Show checks, approvals, deployment status, and merge strategy before enabling merge; use a confirmation sheet with repository, branch, and strategy. This is an implementation recommendation for a documented merge capability. [Mobile docs](https://cursor.com/docs/cloud-agent/mobile)
10. **Cross-surface status contract** — Reuse the same labels and timestamps in the Inbox, detail header, push notification, Lock Screen, and Dynamic Island. Fact basis: all surfaces report agent progress. [Mobile docs](https://cursor.com/docs/cloud-agent/mobile) · [Launch post](https://cursor.com/blog/ios-mobile-app)
11. **Cache-first recovery** — Open the last known Inbox and conversation immediately, mark stale content with its sync time, queue drafts, and reconcile on reconnect. Fact basis: cache-first behavior and background catch-up. [Mobile docs](https://cursor.com/docs/cloud-agent/mobile) · [Mobile help](https://cursor.com/help/ai-features/mobile-app)
12. **Responsive iPad workspace** — Keep the agent list/chat sidebar pinned, open review beside chat, and expand diffs to full width; preserve the same navigation model as iPhone. Fact basis: iPad layout. [Mobile docs](https://cursor.com/docs/cloud-agent/mobile)

## 8. Facts, assumptions, and gaps

- **Verified:** agent orchestration, cloud/local control, Inbox, live stream, follow-ups, voice, commands, artifacts, markup, PR review/merge, notifications, Live Activities, cache-first loading, and iPad split/sidebar behavior.
- **Inference:** exact colors, font choices, corner radii, animation durations, bottom-vs-stack navigation, event-row styling, and merge-confirmation mechanics. Validate these against the current installed build and App Store screenshots before pixel-level implementation.
- **Not claimed:** the app contains an editor, terminal, file browser, or a full offline execution mode; official docs explicitly say it does not.

## Sources

- **Kept:** [Cursor for iOS docs](https://cursor.com/docs/cloud-agent/mobile) — primary feature, platform, navigation, state, and limitation reference.
- **Kept:** [Cursor for iOS help](https://cursor.com/help/ai-features/mobile-app) — setup, account, empty repository, backgrounding, and permissions guidance.
- **Kept:** [Cursor Mobile](https://cursor.com/mobile) — official product framing and representative agent/review content.
- **Kept:** [Build from anywhere with Cursor for iOS](https://cursor.com/blog/ios-mobile-app) — launch workflows, artifacts, notifications, and handoff examples.
- **Kept:** [Cursor, now on iPad](https://cursor.com/changelog/ipad) — Inbox, full PR review, markup, and iPad layout details.
- **Kept:** [Apple App Store listing](https://apps.apple.com/us/app/cursor/id6767085653) — publisher description and public capability summary.
- **Qualified:** [Curio Cursor IDE analysis](https://designbycurio.com/learn/cursor-ide) — useful visual-language analysis, but third-party and desktop-focused.
- **Dropped:** community posts, Reddit, and generic design commentary — anecdotal, duplicative, or not authoritative enough for product facts.
