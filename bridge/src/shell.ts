/**
 * POSIX single-quote escaping: the value survives any shell as one literal
 * argument. Apostrophes become `'\''`; every other metacharacter (`;`, `$`,
 * backtick, `"`, `\`, spaces) is inert inside single quotes.
 */
export function shellQuote(value: string): string {
  return `'${value.replace(/'/g, `'\\''`)}'`;
}
