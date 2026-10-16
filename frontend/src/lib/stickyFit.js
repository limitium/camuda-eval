import { onBeforeUnmount, onMounted, ref, watch } from 'vue';

/*
 * The graph column beside a list stays in view while the list scrolls, without ever being cut: it
 * sticks inside the stage (the scroll area between the top band and the action bar), at the top when
 * it fits, and otherwise only once its bottom is in view — so every row of it can always be reached.
 * Returns the `top` (px) for a `position: sticky` element held in `el`.
 */
export function useStickyFit(el, gap = 16) {
  const top = ref(gap);
  let observer = null;
  let observed = [];

  function fit() {
    const node = el.value;
    const stage = node?.closest('.stage');
    if (!node || !stage) return;
    top.value = Math.min(gap, stage.clientHeight - node.offsetHeight - gap);
  }

  function observe() {
    if (!observer) return;
    observed.forEach((n) => observer.unobserve(n));
    const node = el.value;
    observed = node ? [node, node.closest('.stage')].filter(Boolean) : [];
    observed.forEach((n) => observer.observe(n));
    fit();
  }

  onMounted(() => {
    if (typeof ResizeObserver !== 'undefined') observer = new ResizeObserver(fit);
    observe();
  });
  watch(el, observe, { flush: 'post' });
  onBeforeUnmount(() => observer?.disconnect());

  return top;
}
