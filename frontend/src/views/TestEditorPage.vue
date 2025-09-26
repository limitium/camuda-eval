<template>
  <div class="test-editor-page">
    <a-card :title="`Test Editor: ${baseName}`" :bordered="false">
      <div ref="yamlContainer" class="yaml-container"></div>
    </a-card>
  </div>
</template>

<script setup>
import { onMounted, onBeforeUnmount, ref, computed } from 'vue';
import axios from 'axios';
import * as monaco from 'monaco-editor';
import yamlSchema from '../yaml-schema.json';
import { parseDocument, LineCounter } from 'yaml';
import Ajv from 'ajv';
import { appStore as store } from '../../src/store';
const props = defineProps(['name']);

const yamlName = computed(() => String(props.name).replace(/\.dmn$/, '.yaml'));
const baseName = computed(() => String(props.name).replace(/\.dmn$/, ''));
const yamlContainer = ref(null);
let editor = null;
let modelRef = null;

onMounted(async () => {
  store.setBaseName(baseName.value);

  // Provide a no-op worker to silence Monaco worker lookups in worker-free mode
  try {
    const noOpWorker = () => {
      const blob = new Blob(['self.onmessage=function(){}'], { type: 'text/javascript' });
      return new Worker(URL.createObjectURL(blob));
    };
    const env = { getWorker: noOpWorker };
    if (typeof self !== 'undefined') self.MonacoEnvironment = env;
    if (typeof window !== 'undefined') window.MonacoEnvironment = env;
  } catch {}


  // Load file from store (loads once and caches)
  const fileData = await store.loadFile(baseName.value);
  let yamlText = fileData.yaml || 'tests:\n';
  
  // Always prefer draft if available (contains latest changes)
  const draft = store.getYamlDraft(baseName.value);
  if (draft) {
    yamlText = draft;
  } else {
    // If no draft, use loaded file and create initial draft
    store.setYamlDraft(baseName.value, yamlText);
  }

  // Create model with stable URI for validation context
  const uri = monaco.Uri.parse(`inmemory://model/${baseName.value}.yaml`);
  let model = monaco.editor.getModel(uri);
  if (model) {
    model.setValue(yamlText);
  } else {
    model = monaco.editor.createModel(yamlText, 'yaml', uri);
  }
  modelRef = model;

  editor = monaco.editor.create(yamlContainer.value, {
    model,
    automaticLayout: true,
    wordWrap: 'on',
    minimap: { enabled: false }
  });


  // Close marker/peek widgets when navigating so selection remains usable
  try {
    editor.onDidFocusEditorText(() => {
      try { editor.trigger('source', 'closeMarkersNavigation', null); } catch {}
      try { editor.trigger('source', 'closeReferenceSearch', null); } catch {}
      try { editor.trigger('source', 'closeFindWidget', null); } catch {}
    });
    editor.onDidChangeCursorPosition(() => {
      try { editor.trigger('source', 'closeMarkersNavigation', null); } catch {}
    });
  } catch {}

  // Worker-free schema validation using yaml + ajv
  const ajv = new Ajv({ allErrors: true, strict: false });
  const validate = ajv.compile(yamlSchema);

  function jsonPointerToPath(ptr) {
    if (!ptr) return [];
    return ptr
      .slice(1)
      .split('/')
      .map((s) => s.replace(/~1/g, '/').replace(/~0/g, '~'));
  }

  function findYamlNode(doc, path) {
    let node = doc.contents;
    for (const seg of path) {
      if (!node) return null;
      if (node.type === 'MAP') {
        node = node.get(seg, true);
      } else if (node.type === 'SEQ') {
        const idx = Number(seg);
        node = Number.isInteger(idx) ? node.items[idx] : null;
      } else {
        break;
      }
    }
    return node || doc.contents || null;
  }

  function nodeToRange(lineCounter, node) {
    const dflt = { startLineNumber: 1, startColumn: 1, endLineNumber: 1, endColumn: 1 };
    if (!node || !node.range) return dflt;
    const [s, , e] = node.range;
    const ps = lineCounter.linePos(s);
    const pe = lineCounter.linePos(e);
    return {
      startLineNumber: ps.line,
      startColumn: ps.col,
      endLineNumber: pe.line,
      endColumn: pe.col
    };
  }

  function validateNow() {
    const text = model.getValue();
    const lineCounter = new LineCounter();
    let markers = [];
    try {
      const doc = parseDocument(text, {
        lineCounter,
        keepCstNodes: true,
        prettyErrors: true
      });

      if (doc.errors && doc.errors.length) {
        markers = doc.errors.map((err) => {
          const pos = err.pos ?? [0, 0];
          const ps = lineCounter.linePos(pos[0] || 0);
          const pe = lineCounter.linePos(pos[1] || pos[0] || 0);
          return {
            severity: monaco.MarkerSeverity.Error,
            message: String(err),
            startLineNumber: ps.line,
            startColumn: ps.col,
            endLineNumber: pe.line,
            endColumn: pe.col
          };
        });
      } else {
        const data = doc.toJSON();
        validate(data);
        if (validate.errors && validate.errors.length) {
          markers = validate.errors.map((err) => {
            const path = jsonPointerToPath(err.instancePath || '');
            let node = findYamlNode(doc, path);
            if (err.keyword === 'required' && node && node.type !== 'MAP') {
              const parent = findYamlNode(doc, path.slice(0, -1));
              if (parent) node = parent;
            }
            const range = nodeToRange(lineCounter, node);
            return {
              severity: monaco.MarkerSeverity.Error,
              message: ajv.errorsText([err], { separator: '\n' }),
              ...range
            };
          });
        }
      }
    } catch (e) {
      markers = [
        {
          severity: monaco.MarkerSeverity.Error,
          message: String(e),
          startLineNumber: 1,
          startColumn: 1,
          endLineNumber: 1,
          endColumn: 1
        }
      ];
    }
    monaco.editor.setModelMarkers(model, 'schema', markers);
    if (!markers.length) {
      try { editor.trigger('source', 'closeMarkersNavigation', null); } catch {}
      try { editor.trigger('source', 'closeReferenceSearch', null); } catch {}
      try { editor.trigger('source', 'closeFindWidget', null); } catch {}
    }
  }

  const debounce = (fn, ms) => {
    let t;
    return (...a) => {
      clearTimeout(t);
      t = setTimeout(() => fn(...a), ms);
    };
  };

  const revalidateDebounced = debounce(() => validateNow(), 200);
  model.onDidChangeContent(revalidateDebounced);
  model.onDidChangeContent(() => {
    try { store.setYamlDraft(baseName.value, model.getValue()); } catch {}
  });
  validateNow();

  // initialize draft on mount
  try { store.setYamlDraft(baseName.value, model.getValue()); } catch {}
});

onBeforeUnmount(() => {
  if (editor) editor.dispose();
  if (modelRef) {
    try { modelRef.dispose(); } catch {}
    modelRef = null;
  }
});
</script>

<style scoped>
.yaml-container {
  height: calc(100vh - 120px);
  border: 1px solid #d9d9d9;
}
</style>
