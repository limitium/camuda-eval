import { onBeforeUnmount, reactive, watchEffect } from 'vue';

/*
 * The bottom bar: what just happened on the left, the one next action on the right. A screen
 * declares its next action here instead of drawing its own filled button, which is what keeps it to
 * one per screen.
 */
export const bar = reactive({
  primary: null, // { label, run, disabled, busy }
  secondary: [], // [{ label, run, disabled, danger }]
  hint: null // what the next action is waiting for, in words
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
