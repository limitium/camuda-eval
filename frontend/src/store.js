import { reactive } from 'vue';
import axios from 'axios';

// Simple global store for current file and saving logic
export const appStore = reactive({
  baseName: '', // file name without extension
  saving: false,
  error: null, // current error message
  getDmnContent: null, // () => Promise<string> | string
  getYamlContent: null, // () => Promise<string> | string
  yamlDraftByName: {}, // { [baseName: string]: string }
  dmnDraftByName: {}, // { [baseName: string]: string }
  loadedFiles: {}, // { [baseName: string]: { dmn: string, yaml: string, loaded: boolean } }

  setBaseName(name) {
    this.baseName = name || '';
  },


  setYamlDraft(name, text) {
    if (!name) return;
    this.yamlDraftByName[name] = String(text ?? '');
  },

  getYamlDraft(name) {
    if (!name) return '';
    return this.yamlDraftByName[name] ?? '';
  },

  setDmnDraft(name, xml) {
    if (!name) return;
    this.dmnDraftByName[name] = String(xml ?? '');
  },

  getDmnDraft(name) {
    if (!name) return '';
    return this.dmnDraftByName[name] ?? '';
  },

  setError(message) {
    this.error = message;
  },

  clearError() {
    this.error = null;
  },

  async loadFile(name) {
    if (!name) return;
    if (this.loadedFiles[name]?.loaded) return this.loadedFiles[name];
    
    try {
      this.clearError();
      const [dmnRes, yamlRes] = await Promise.all([
        axios.get(`/api/dmn/${name}.dmn`, { responseType: 'text' }).catch(() => ({ data: '' })),
        axios.get(`/api/yaml/${name}.yaml`, { responseType: 'text' }).catch(() => ({ data: '' }))
      ]);
      
      const dmnContent = dmnRes.data || '';
      const yamlContent = yamlRes.data || '';
      
      this.loadedFiles[name] = {
        dmn: dmnContent,
        yaml: yamlContent,
        loaded: true
      };
      
      // Always initialize drafts when loading a file
      this.setDmnDraft(name, dmnContent);
      this.setYamlDraft(name, yamlContent);
      
      return this.loadedFiles[name];
    } catch (error) {
      this.setError(`Failed to load file ${name}: ${error.message}`);
      return { dmn: '', yaml: '', loaded: false };
    }
  },

  async saveAll() {
    // this can be undefined if method passed unbound; fallback to appStore
    const state = this && this.baseName !== undefined ? this : appStore;
    if (!state.baseName) return;
    state.saving = true;
    try {
      state.clearError();
      const tasks = [];
      
      // Always save both files using draft content (independent of active tab)
      const dmnContent = state.getDmnDraft(state.baseName);
      const yamlContent = state.getYamlDraft(state.baseName);
      
      console.log('Save: DMN content length:', dmnContent.length);
      console.log('Save: YAML content length:', yamlContent.length);
      
      // Always save both files
      if (dmnContent) {
        tasks.push(axios.put(`/api/dmn/${encodeURIComponent(state.baseName)}.dmn`, dmnContent, { headers: { 'Content-Type': 'application/xml' } }));
      }
      if (yamlContent) {
        tasks.push(axios.put(`/api/yaml/${encodeURIComponent(state.baseName)}.yaml`, yamlContent, { headers: { 'Content-Type': 'text/plain' } }));
      }
      
      await Promise.all(tasks);
      
      // Update loaded files cache with saved content
      if (state.loadedFiles[state.baseName]) {
        state.loadedFiles[state.baseName].dmn = dmnContent;
        state.loadedFiles[state.baseName].yaml = yamlContent;
      }
    } catch (error) {
      state.setError(`Failed to save files: ${error.message}`);
    } finally {
      state.saving = false;
    }
  },

  clearFileData(name) {
    if (!name) return;
    
    // Clear drafts
    delete this.yamlDraftByName[name];
    delete this.dmnDraftByName[name];
    
    // Clear loaded files cache
    delete this.loadedFiles[name];
    
    // If this is the current file, clear baseName
    if (this.baseName === name) {
      this.baseName = '';
    }
    
    console.log(`Cleared store data for file: ${name}`);
  }
});


