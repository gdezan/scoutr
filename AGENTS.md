## Git workflow

- Work directly on `main` and commit changes there unless the user explicitly specifies another branch or workflow.

## Vision

When a task involves an image — a screenshot, mockup, rendered UI, or diagram — actually look at it: delegate the description to a vision-capable pi running in a sibling herdr pane (this works when `HERDR_ENV=1`, i.e. you run inside a herdr pane). Do not guess what an image contains.

1. Split a sibling pane without stealing focus, then parse the new pane id from `.result.pane.pane_id`:

   ```bash
   herdr pane split --current --direction right --cwd "$PWD" --no-focus
   ```

2. Run non-interactive pi with the vision model, attach the image with `@path` (one or more), write the answer to a temp file, and echo a sentinel the wait can match:

   ```bash
   herdr pane run <pane-id> 'pi -p --model opencode-go/gpt-5.6-luna @<abs-image-path> "Describe this image in detail: layout, text, colors, and any UI state or errors visible." > /tmp/vision-answer.md 2>&1; echo VISION_DONE'
   ```

3. Wait for the sentinel, then read the answer:

   ```bash
   herdr pane wait-output <pane-id> --match VISION_DONE --timeout 180000
   cat /tmp/vision-answer.md
   ```

4. Close the pane you created once the description is in hand: `herdr pane close <pane-id>`.

Done when the description answers the specific question you had about the image. An empty file means a bad image path or a failed model call — fix and re-run. If `opencode-go/gpt-5.6-luna` no longer works, pick a model with `images: yes` from `pi --list-models`.

## Communication principles

- When speaking with the user, use reader-centred plain language adapted to an experienced software engineer.
- State the main point first, then provide only the context needed to understand or act on it.
- Use precise technical terms when they add meaning, but avoid unnecessary jargon and define terms that may be ambiguous or domain-specific.
- Organize complex information with clear headings, bullets, examples, and explicit distinctions between facts, assumptions, options, risks, and recommendations.
- Do not oversimplify technical subjects; simplify the wording and structure instead.
- Prefer concrete, concise, actionable explanations over background, repetition, hedging, or conversational filler.

## Engineering principles

- Do not preserve backward compatibility. Remove obsolete paths instead of adding compatibility layers, fallbacks, or migrations.
- Choose the simplest implementation that fully meets the current requirements. Avoid speculative abstractions, configuration, and indirection.
- Grow the system in layers. Start from the smallest version that works end to end, and add each new capability on top of a product that already works. Never trade a working product for unfinished complexity. Keep components modular and concerns clearly separated. Prefer established, well-maintained libraries when they reduce overall complexity or improve reliability. Do not reimplement common functionality without a clear reason.
- Lean on the dependencies already in the project before writing your implementation or adding packages. Do not assume a library lacks capability without checking its documentation and types.
- Make architectural decisions for the long term. Do not accept a stopgap that only works for now and is meant to be replaced later.
