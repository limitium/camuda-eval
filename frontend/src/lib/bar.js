import { onBeforeUnmount, reactive, watchEffect } from 'vue';

/*
 * The bottom bar: what just happened on the left, the one next action on the right. A screen
 * declares its next action here instead of drawing its own filled button, which is what keeps it to
 * one per screen.
 */
export const bar = reactive({
  primary: null, // { label, run, disabled, busy }
  secondary: [], // [{ label, run, disabled, danger }]
  hint: null, // what the next action is waiting for, in words
  running: null, // the action being run, whoever pressed it

  /** The buttons that can be pressed now, the next action first. */
  get pressable() {
    return [this.primary, ...this.secondary].filter((a) => a && !a.disabled && !a.busy);
  },

  /** Presses a button: the same run as a click, busy for as long as it takes, one at a time. */
  async press(action) {
    if (!action || this.running) return;
    this.running = action;
    try {
      await action.run();
    } finally {
      this.running = null;
    }
  }
});

export function useBar(describe) {
  const stop = watchEffect(() => {
    const next = describe() || {};
    bar.primary = next.primary || null;
    bar.secondary = next.secondary || [];
    bar.hint = next.hint || null;
  });
  onBeforeUnmount(() => {
    stop();
    bar.primary = null;
    bar.secondary = [];
    bar.hint = null;
  });
}
