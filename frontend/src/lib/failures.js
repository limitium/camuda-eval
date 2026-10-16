/* A test failure in the engine's words, said as what to do about it. */
export function plainFailure(error) {
  const text = String(error || '').split('\n')[0];
  const name = text.match(/no variable found for name '([^']+)'/);
  if (name) {
    return `“${name[1]}” is read as the name of a value, and nothing gives one by that name. If it is meant as text, write it in quotes in the table: "${name[1]}".`;
  }
  const differ = text.match(/^AssertionError: (\S+): Values differ: expected=(.*), actual=(.*)$/);
  if (differ) return `${differ[1]}: expected ${differ[2]}, the table gave ${differ[3]}.`;
  return text
    .replace(/^\w+(Exception|Error): /, '')
    .replace(/^Error evaluating decision: \S+ -> /, '')
    .replace(/^\w+(Exception|Error): /, '');
}
