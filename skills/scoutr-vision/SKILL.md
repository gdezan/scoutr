---
name: scoutr-vision
description: Inspect Scoutr screenshots, mockups, rendered UI, or diagrams when the active agent cannot directly use the image. Use only when visual evidence is required.
---

# Scoutr visual evidence

Use this only when the task depends on visual evidence and the active model
cannot inspect the image directly. Do not infer visual correctness from source
code alone.

Use the global `herdr-agent-delegation` skill for sibling-pane creation, a
vision-capable agent, lifecycle waits, attachment handling, transcript reads,
blocked-agent recovery, and cleanup. The prompt should ask for only the visual
evidence relevant to the acceptance question: layout, text, colors, state,
interaction cues, and visible errors.

Done means the returned evidence answers the specific visual question that
triggered the workflow. An empty answer or missing attachment is a delegation
failure to diagnose, not evidence that the UI passed.
