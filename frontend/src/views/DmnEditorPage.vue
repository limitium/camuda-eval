<template>
  <div class="dmn-editor-page">
    <a-card :title="`DMN Editor: ${baseName}`" :bordered="false">
      <div class="toolbar">
        <a-space size="small" align="center">
          <a-tooltip title="Zoom out">
            <a-button size="small" shape="circle" @click="zoomOut">-</a-button>
          </a-tooltip>
          <a-tooltip title="Zoom in">
            <a-button size="small" shape="circle" @click="zoomIn">+</a-button>
          </a-tooltip>
          <a-tooltip title="Fit to screen">
            <a-button size="small" @click="fitView">Fit</a-button>
          </a-tooltip>
          <a-divider type="vertical" />
          <a-tooltip :title="xmlMode ? 'Switch to visual' : 'Switch to XML'">
            <a-switch v-model:checked="xmlMode" checked-children="XML" un-checked-children="Visual" />
          </a-tooltip>
        </a-space>
      </div>
      <div v-show="!xmlMode" class="editor-container">
        <div ref="dmnContainer" class="dmn-container"></div>
        <div ref="propertiesContainer" class="properties-container" :style="{ display: isDecisionOpen ? 'none' : 'block' }"></div>
      </div>
      <div v-show="xmlMode" ref="xmlContainer" class="xml-container"></div>
    </a-card>
  </div>
</template>

<script setup>
import { onMounted, onBeforeUnmount, ref, computed, watch } from 'vue';
import axios from 'axios';
import DmnModeler from 'dmn-js/lib/Modeler';
import { DmnPropertiesPanelModule, DmnPropertiesProviderModule } from 'dmn-js-properties-panel';
import * as monaco from 'monaco-editor';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker&module';
import 'dmn-js/dist/assets/diagram-js.css';
import 'dmn-js/dist/assets/dmn-js-shared.css';
import 'dmn-js/dist/assets/dmn-js-decision-table.css';
import 'dmn-js/dist/assets/dmn-js-decision-table-controls.css';
import 'dmn-js/dist/assets/dmn-js-drd.css';
import 'dmn-js/dist/assets/dmn-font/css/dmn.css';
import 'dmn-js-properties-panel/dist/assets/properties-panel.css';

const props = defineProps(['name']);
import { appStore as store } from '../../src/store';

const baseName = computed(() => String(props.name).replace(/\.dmn$/, ''));

const dmnContainer = ref(null);
const xmlContainer = ref(null);
const propertiesContainer = ref(null);
const isDecisionOpen = ref(false);
let modeler;
let propertiesPanel;
let xmlEditor = null;
const xmlMode = ref(false);
let dmnDraft = '';
let saveTimeout = null;
let periodicSaveInterval = null;

// Debounced save function to avoid excessive saves
const debouncedSave = async () => {
  if (saveTimeout) {
    clearTimeout(saveTimeout);
  }
  saveTimeout = setTimeout(async () => {
    try {
      if (modeler) {
        const { xml } = await modeler.saveXML({ format: true });
        store.setDmnDraft(baseName.value, xml);
      }
    } catch (error) {
      console.warn('Failed to save DMN draft:', error);
    }
  }, 500); // Debounce for 500ms
};

// Periodic save as fallback to ensure no changes are lost
const startPeriodicSave = () => {
  if (periodicSaveInterval) {
    clearInterval(periodicSaveInterval);
  }
  periodicSaveInterval = setInterval(async () => {
    try {
      if (modeler) {
        const { xml } = await modeler.saveXML({ format: true });
        store.setDmnDraft(baseName.value, xml);
      }
    } catch (error) {
      console.warn('Failed to save DMN draft (periodic):', error);
    }
  }, 2000); // Save every 2 seconds as fallback
};

onMounted(async () => {
  // register store providers
  store.setBaseName(String(props.name).replace(/\.dmn$/, ''));
  // ensure visual mode by default
  xmlMode.value = false;
  try {
    // Load file from store (loads once and caches)
    const fileData = await store.loadFile(baseName.value);
    let dmnXml = fileData.dmn;
    
    // Always prefer draft if available (contains latest changes)
    const draft = store.getDmnDraft(baseName.value);
    if (draft) {
      dmnXml = draft;
    } else {
      // If no draft, use loaded file and create initial draft
      store.setDmnDraft(baseName.value, dmnXml);
    }
    modeler = new DmnModeler({ 
      container: dmnContainer.value,
      width: '100%',
      height: '100%',
      drd: {
        additionalModules: [ DmnPropertiesPanelModule, DmnPropertiesProviderModule ],
        propertiesPanel: { parent: propertiesContainer.value }
      },
      decisionTable: {
        additionalModules: [ DmnPropertiesPanelModule, DmnPropertiesProviderModule ],
        propertiesPanel: { parent: propertiesContainer.value }
      },
      literalExpression: {
        additionalModules: [ DmnPropertiesPanelModule, DmnPropertiesProviderModule ],
        propertiesPanel: { parent: propertiesContainer.value }
      }
    });
    await modeler.importXML(dmnXml);
    // save current state as draft
    try {
      const { xml } = await modeler.saveXML({ format: true });
      store.setDmnDraft(baseName.value, xml);
    } catch {}
    // open DRD view by default
    try {
      const views = typeof modeler.getViews === 'function' ? modeler.getViews() : [];
      const drdView = views.find(v => v.type === 'drd');
      if (drdView) await modeler.open(drdView);
      isDecisionOpen.value = false;
    } catch {}
    // center on load
    fitView();
    // fit when user switches to DRD via internal button
    try {
      modeler.on && modeler.on('views.changed', ({ activeView }) => {
        if (activeView && activeView.type === 'drd') {
          // next tick to allow layout
          setTimeout(() => fitView(), 0);
        }
        const t = activeView && activeView.type;
        isDecisionOpen.value = t === 'decisionTable' || t === 'literalExpression';
      });
      
      // Update draft whenever model changes
      modeler.on && modeler.on('commandStack.changed', debouncedSave);
      
      // Capture element changes (including attribute changes)
      modeler.on && modeler.on('element.changed', debouncedSave);
      
      // Capture selection changes
      modeler.on && modeler.on('selection.changed', debouncedSave);
      
      // Capture properties panel changes
      modeler.on && modeler.on('propertiesPanel.changed', debouncedSave);
      
      // Capture any other model changes
      modeler.on && modeler.on('model.changed', debouncedSave);
      
      // Add comprehensive event listeners for attribute changes
      modeler.on && modeler.on('element.update', debouncedSave);
      modeler.on && modeler.on('element.attribute.changed', debouncedSave);
      modeler.on && modeler.on('element.property.changed', debouncedSave);
      
      // Add direct event listener to properties panel if available
      if (propertiesPanel && typeof propertiesPanel.on === 'function') {
        propertiesPanel.on('change', debouncedSave);
        propertiesPanel.on('input', debouncedSave);
        propertiesPanel.on('blur', debouncedSave);
        propertiesPanel.on('focusout', debouncedSave);
      }
      
      // Also listen for DOM changes in the properties panel
      if (propertiesContainer.value) {
        const observer = new MutationObserver(debouncedSave);
        observer.observe(propertiesContainer.value, {
          childList: true,
          subtree: true,
          attributes: true,
          attributeOldValue: true
        });
        
        // Add event listeners to all input elements in the properties panel
        const addInputListeners = () => {
          if (!propertiesContainer.value) return;
          const inputs = propertiesContainer.value.querySelectorAll('input, textarea, select');
          inputs.forEach(input => {
            input.addEventListener('change', debouncedSave);
            input.addEventListener('input', debouncedSave);
            input.addEventListener('blur', debouncedSave);
          });
        };
        
        // Add listeners with a small delay to ensure DOM is ready
        setTimeout(() => {
          if (propertiesContainer.value) {
            addInputListeners();
          }
        }, 100);
        
        observer.observe(propertiesContainer.value, {
          childList: true,
          subtree: true,
          attributes: false
        });
        
        // Re-add listeners when new elements are added
        const mutationObserver = new MutationObserver(() => {
          if (propertiesContainer.value) {
            addInputListeners();
          }
        });
        mutationObserver.observe(propertiesContainer.value, {
          childList: true,
          subtree: true
        });
      }
      
      // Start periodic save as fallback
      startPeriodicSave();
    } catch {}
  } catch (error) {
    console.error('Failed to load DMN:', error);
    // Create empty DMN modeler with minimal content
    const emptyDmn = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" 
             xmlns:dmndi="https://www.omg.org/spec/DMN/20191111/DMNDI/" 
             xmlns:dc="http://www.omg.org/spec/DMN/20180521/DC/" 
             xmlns:di="http://www.omg.org/spec/DMN/20180521/DI/" 
             xmlns:kie="http://www.drools.org/kie/dmn/1.2" 
             xmlns:feel="http://www.omg.org/spec/DMN/20180521/FEEL/" 
             xmlns:biodi="http://bpmn.io/schema/dmn/biodi/2.0" 
             id="Definitions_1" 
             name="DMN" 
             typeLanguage="http://www.omg.org/spec/DMN/20180521/FEEL/" 
             namespace="http://camunda.org/schema/1.0/dmn">
  <decision id="Decision_1" name="Decision 1">
    <decisionTable id="DecisionTable_1" hitPolicy="UNIQUE">
      <input id="Input_1" label="Input 1">
        <inputExpression id="InputExpression_1" typeRef="string">
          <text>x</text>
        </inputExpression>
      </input>
      <output id="Output_1" label="Output 1" name="out" typeRef="string"/>
      <rule id="Rule_1">
        <inputEntry id="InputEntry_1"><text>"test"</text></inputEntry>
        <outputEntry id="OutputEntry_1"><text>"OK"</text></outputEntry>
      </rule>
    </decisionTable>
  </decision>
</definitions>`;
    modeler = new DmnModeler({ 
      container: dmnContainer.value,
      width: '100%',
      height: '100%',
      drd: {
        additionalModules: [ DmnPropertiesPanelModule, DmnPropertiesProviderModule ],
        propertiesPanel: { parent: propertiesContainer.value }
      },
      decisionTable: {
        additionalModules: [ DmnPropertiesPanelModule, DmnPropertiesProviderModule ],
        propertiesPanel: { parent: propertiesContainer.value }
      },
      literalExpression: {
        additionalModules: [ DmnPropertiesPanelModule, DmnPropertiesProviderModule ],
        propertiesPanel: { parent: propertiesContainer.value }
      }
    });
    await modeler.importXML(emptyDmn);
    // save current state as draft
    try {
      const { xml } = await modeler.saveXML({ format: true });
      store.setDmnDraft(baseName.value, xml);
    } catch {}
    try {
      const views = typeof modeler.getViews === 'function' ? modeler.getViews() : [];
      const drdView = views.find(v => v.type === 'drd');
      if (drdView) await modeler.open(drdView);
      isDecisionOpen.value = false;
    } catch {}
    fitView();
  }
});

onBeforeUnmount(() => {
  if (saveTimeout) {
    clearTimeout(saveTimeout);
  }
  if (periodicSaveInterval) {
    clearInterval(periodicSaveInterval);
  }
  if (modeler) modeler.destroy();
});


function getService(name) {
  try {
    if (!modeler) return null;
    if (typeof modeler.get === 'function') return modeler.get(name);
    if (typeof modeler.getActiveViewer === 'function') return modeler.getActiveViewer().get(name);
  } catch {}
  return null;
}

function getCanvas() {
  return getService('canvas');
}

function zoomIn() {
  const canvas = getCanvas();
  if (!canvas) return;
  const current = canvas.zoom();
  canvas.zoom(Math.min(current + 0.2, 4));
}

function zoomOut() {
  const canvas = getCanvas();
  if (!canvas) return;
  const current = canvas.zoom();
  canvas.zoom(Math.max(current - 0.2, 0.2));
}

function fitView() {
  const canvas = getCanvas();
  if (!canvas) return;
  try { canvas.zoom('fit-viewport', 'auto'); } catch {}
}

watch(xmlMode, async (on) => {
  if (on) {
    // switching to XML editor
    const workerEnv = {
      getWorker() {
        return new EditorWorker();
      }
    };
    self.MonacoEnvironment = workerEnv;
    if (typeof window !== 'undefined') window.MonacoEnvironment = workerEnv;
    const { xml } = await modeler.saveXML({ format: true });
    xmlEditor = monaco.editor.create(xmlContainer.value, {
      value: xml,
      language: 'xml',
      automaticLayout: true,
      minimap: { enabled: false },
      wordWrap: 'on'
    });
  } else {
    // switching back to visual -> re-import XML
    if (xmlEditor) {
      const xml = xmlEditor.getValue();
      xmlEditor.dispose();
      xmlEditor = null;
      await modeler.importXML(xml);
      // update draft when switching back from XML
      try { store.setDmnDraft(baseName.value, xml); } catch {}
      try {
        const views = typeof modeler.getViews === 'function' ? modeler.getViews() : [];
        const drdView = views.find(v => v.type === 'drd');
        if (drdView) await modeler.open(drdView);
      } catch {}
      fitView();
    }
  }
});

</script>

<style scoped>
.dmn-editor-page {
  height: calc(100vh - 64px); /* Subtract header height */
  background-color: #ffffff !important;
  color: #262626 !important;
  filter: none !important;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.editor-container {
  display: flex;
  height: calc(100vh - 120px); /* Subtract header + card padding + button */
  gap: 8px;
}

.dmn-container {
  flex: 1;
  border: 1px solid #d9d9d9;
  background: #fff;
  overflow: hidden;
  position: relative;
  filter: none !important;
}

.properties-container {
  width: 300px;
  border: 1px solid #d9d9d9;
  background: #fff;
  overflow-y: auto;
  filter: none !important;
}

.xml-container {
  height: calc(100vh - 120px);
  width: 100%;
  border: 1px solid #d9d9d9;
  background: #fff;
  overflow: hidden;
}


/* Force all DMN elements to fill the container */
.dmn-container :deep(.djs-container) {
  height: 100% !important;
  width: 100% !important;
  position: absolute !important;
  top: 0 !important;
  left: 0 !important;
  background-color: #ffffff !important;
  filter: none !important;
}

.dmn-container :deep(.djs-palette) {
  max-height: 180px !important; /* show header and ~4 entries */
  overflow: hidden !important;
  width: 200px !important;
  background-color: #ffffff !important;
  filter: none !important;
}

.dmn-container :deep(.djs-canvas) {
  height: 100% !important;
  width: calc(100% - 200px) !important;
  left: 200px !important;
  position: absolute !important;
  background-color: #ffffff !important;
  filter: none !important;
  /* ensure canvas not overlapped by truncated palette */
  pointer-events: auto !important;
}

.dmn-container :deep(.djs-drd-container) {
  height: 100% !important;
  width: 100% !important;
  background-color: #ffffff !important;
  filter: none !important;
}

.dmn-container :deep(.djs-content) {
  height: 100% !important;
  width: 100% !important;
  background-color: #ffffff !important;
  filter: none !important;
}

.dmn-container :deep(.djs-element) {
  height: 100% !important;
  width: 100% !important;
  background-color: #ffffff !important;
  filter: none !important;
}

/* Override Dark Reader on DMN elements */
.dmn-container :deep(*) {
  filter: none !important;
}

/* Force light theme on all DMN internal elements */
.dmn-container :deep(.djs-palette),
.dmn-container :deep(.djs-canvas),
.dmn-container :deep(.djs-drd-container),
.dmn-container :deep(.djs-content),
.dmn-container :deep(.djs-element),
.dmn-container :deep(.djs-container) {
  background-color: #ffffff !important;
  color: #262626 !important;
  filter: none !important;
}

/* DMN Icon and Button Styling */
.dmn-container :deep(.dmn-icon-decision-table) {
  background-color: #ffffff !important;
  color: #262626 !important;
  border: 1px solid #d9d9d9 !important;
  filter: none !important;
}

.dmn-container :deep(.dmn-icon-decision-table:hover) {
  background-color: #f5f5f5 !important;
  border-color: #1890ff !important;
  filter: none !important;
}

.dmn-container :deep(.dmn-icon-decision-table:before) {
  color: #262626 !important;
  filter: none !important;
}

/* Ensure DMN icons are visible */
.dmn-container :deep([class*="dmn-icon-"]) {
  background-color: #ffffff !important;
  color: #262626 !important;
  filter: none !important;
}

.dmn-container :deep([class*="dmn-icon-"]:before) {
  color: #262626 !important;
  filter: none !important;
}

/* DMN DRD specific styling */
.dmn-container :deep(.dmn-drd-container) {
  background-color: #ffffff !important;
  color: #262626 !important;
  filter: none !important;
}

.dmn-container :deep(.dmn-decision) {
  background-color: #ffffff !important;
  color: #262626 !important;
  border-color: #d9d9d9 !important;
  filter: none !important;
}

.dmn-container :deep(.dmn-decision:hover) {
  background-color: #f5f5f5 !important;
  border-color: #1890ff !important;
  filter: none !important;
}

</style>
