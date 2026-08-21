/**
 * Quota window spans.
 *
 * The app draws an elapsed-time guide across each meter, which needs to know how
 * long the window runs — not just when it resets. Providers report the reset
 * instant reliably but the span only sometimes, so it is derived from the window
 * label here and shared, rather than each provider carrying its own table.
 */

const HOUR = 60 * 60;
const DAY = 24 * HOUR;

const FIXED_WINDOW_SECONDS = new Map<string, number>([
  ["5h", 5 * HOUR],
  ["7d", 7 * DAY],
  ["day", DAY],
  ["wk", 7 * DAY],
]);


/**
 * Seconds in the calendar month ending at `resetAt`.
 *
 * Monthly caps ride a billing anchor, so the span is 28-31 days depending on
 * where the anchor falls; anchors past the length of the previous month (the
 * 31st, say) clamp to its last day, the way billing itself does.
 */
export function calendarMonthSeconds(resetAt: number): number {
  const reset = new Date(resetAt * 1000);
  const anchorDay = reset.getUTCDate();
  const start = new Date(reset);
  // Move to the 1st first, so shifting the month cannot overflow into the next.
  start.setUTCDate(1);
  start.setUTCMonth(start.getUTCMonth() - 1);
  const daysInStartMonth = new Date(Date.UTC(start.getUTCFullYear(), start.getUTCMonth() + 1, 0)).getUTCDate();
  start.setUTCDate(Math.min(anchorDay, daysInStartMonth));
  return Math.round((reset.getTime() - start.getTime()) / 1000);
}

/** Span of a labeled quota window, or undefined when it cannot be known. */
export function windowSecondsFor(label: string, resetAt?: number): number | undefined {
  const key = label.trim().toLowerCase();
  const fixed = FIXED_WINDOW_SECONDS.get(key);
  if (fixed !== undefined) return fixed;
  if (key === "mo" && resetAt !== undefined) return calendarMonthSeconds(resetAt);
  return undefined;
}
