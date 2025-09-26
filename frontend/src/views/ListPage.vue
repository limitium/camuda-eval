<template>
  <a-card :bordered="false">
    <template #title>
      <div style="display:flex;align-items:center;justify-content:space-between">
        <span>DMN Files</span>
        <a-button type="primary" @click="openCreate">Create DMN</a-button>
      </div>
    </template>
    <a-table :data-source="files" :columns="columns" row-key="name" :pagination="false" :custom-row="customRow">
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'name'">
          {{ toBase(record.name) }}
        </template>
        <template v-else-if="column.dataIndex === 'modified'">
          {{ formatTimeAgo(record.modified) }}
        </template>
        <template v-else-if="column.key === 'actions'">
          <a-button size="small" type="link" danger @click.stop="confirmDelete(record.name)">Delete</a-button>
        </template>
      </template>
    </a-table>
  </a-card>

  <a-modal v-model:open="createOpen" title="Create DMN" :confirm-loading="creating" @ok="createDmn" @cancel="closeCreate">
    <a-form @submit.prevent>
      <a-form-item label="File name">
        <a-input v-model:value="createName" placeholder="example" @keyup.enter="createDmn" />
      </a-form-item>
    </a-form>
  </a-modal>

  <a-modal v-model:open="deleteOpen" title="Delete DMN File" :confirm-loading="deleting" @ok="deleteFile" @cancel="closeDelete">
    <p>Are you sure you want to delete <strong>{{ deleteFileName }}</strong>?</p>
    <p style="color: #ff4d4f; font-weight: bold;">This action is irreversible and will permanently delete the DMN file and its associated YAML test file.</p>
  </a-modal>
  
</template>

<script setup>
import { onMounted, ref } from 'vue';
import axios from 'axios';
import { useRouter } from 'vue-router';
import { appStore as store } from '../store';

const router = useRouter();
const files = ref([]);
const createOpen = ref(false);
const createName = ref('');
const creating = ref(false);
const deleting = ref(false);
const deleteOpen = ref(false);
const deleteFileName = ref('');

const columns = [
  { title: 'Name', dataIndex: 'name' },
  { title: 'Modified', dataIndex: 'modified' },
  { title: 'Actions', key: 'actions' }
];

onMounted(async () => {
  await refresh();
});

function goEdit(record) {
  // Handle both direct name parameter and row click event
  const name = typeof record === 'string' ? record : record.name;
  router.push(`/dmn/${name}`);
}

function formatTimeAgo(dateString) {
  if (!dateString) return 'Unknown';
  
  const date = new Date(dateString);
  const now = new Date();
  const diffInSeconds = Math.floor((now - date) / 1000);
  
  if (diffInSeconds < 60) return 'Just now';
  if (diffInSeconds < 3600) return `${Math.floor(diffInSeconds / 60)} minutes ago`;
  if (diffInSeconds < 86400) return `${Math.floor(diffInSeconds / 3600)} hours ago`;
  if (diffInSeconds < 2592000) return `${Math.floor(diffInSeconds / 86400)} days ago`;
  if (diffInSeconds < 31536000) return `${Math.floor(diffInSeconds / 2592000)} months ago`;
  return `${Math.floor(diffInSeconds / 31536000)} years ago`;
}

const customRow = (record) => {
  return {
    onClick: () => goEdit(record),
    style: { cursor: 'pointer' }
  };
};

async function refresh() {
  const { data } = await axios.get('/api/dmn');
  files.value = data;
}

function openCreate() {
  createName.value = '';
  createOpen.value = true;
}

function closeCreate() {
  createOpen.value = false;
}

function confirmDelete(name) {
  deleteFileName.value = toBase(name);
  deleteOpen.value = true;
}

function closeDelete() {
  deleteOpen.value = false;
  deleteFileName.value = '';
}

async function createDmn() {
  const rawName = (createName.value || '').trim();
  if (!rawName) return;
  let name = rawName.endsWith('.dmn') ? rawName : rawName + '.dmn';
  creating.value = true;
  try {
    const xml = minimalDmn();
    
    // Create DMN file - backend will automatically create paired YAML
    const dmnBlob = new Blob([xml], { type: 'application/xml' });
    const dmnForm = new FormData();
    dmnForm.append('file', dmnBlob, name);
    
    await axios.post(`/api/dmn?name=${encodeURIComponent(name)}`, dmnForm);
    
    // Update the auto-created YAML with our minimal test
    const yaml = minimalYaml();
    await axios.put(`/api/yaml/${encodeURIComponent(name.replace('.dmn', '.yaml'))}`, yaml, {
      headers: { 'Content-Type': 'text/plain' }
    });
    
    createOpen.value = false;
    await refresh();
    goEdit(name);
  } catch (error) {
    store.setError(`Failed to create DMN file: ${error.message}`);
  } finally {
    creating.value = false;
  }
}

function minimalDmn() {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions id="Definitions_1" name="Definitions"
  namespace="http://camunda.org/schema/1.0/dmn"
  xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"
  xmlns:feel="https://www.omg.org/spec/DMN/20191111/FEEL/"
  xmlns:camunda="http://camunda.org/schema/1.0/dmn">
  <decision id="Decision_1" name="Decision">
    <decisionTable id="DecisionTable_1" hitPolicy="FIRST">
      <input id="Input_1">
        <inputExpression id="InputExpression_1" typeRef="string"><text>x</text></inputExpression>
      </input>
      <output id="Output_1" name="out" typeRef="string" />
      <rule id="Rule_1">
        <inputEntry id="InputEntry_1"><text>-</text></inputEntry>
        <outputEntry id="OutputEntry_1"><text>"OK"</text></outputEntry>
      </rule>
    </decisionTable>
  </decision>
</definitions>`;
}

function minimalYaml() {
  return `tests:
  - decision: "Decision_1"
    description: "Simple test case"
    in:
      x: "test"
    out: "OK"
`;
}

async function deleteFile() {
  if (!deleteFileName.value) return;
  
  const name = deleteFileName.value + '.dmn';
  const baseName = deleteFileName.value;
  deleting.value = true;
  try {
    // Delete DMN file (backend automatically deletes paired YAML and run folder)
    await axios.delete(`/api/dmn/${name}`);
    
    // Clear frontend store data for this file
    store.clearFileData(baseName);
    
    await refresh();
    deleteOpen.value = false;
    deleteFileName.value = '';
  } catch (error) {
    store.setError(`Failed to delete file: ${error.message}`);
  } finally {
    deleting.value = false;
  }
}

function toBase(name) {
  return String(name).replace(/\.(dmn|yaml)$/i, '');
}
</script>

<style scoped>
/* Force light theme on list page */
:deep(.ant-card) {
  background-color: #ffffff !important;
  color: #262626 !important;
  filter: none !important;
}

:deep(.ant-table) {
  background-color: #ffffff !important;
  color: #262626 !important;
  filter: none !important;
}

:deep(.ant-table-thead > tr > th) {
  background-color: #fafafa !important;
  color: #262626 !important;
  filter: none !important;
}

:deep(.ant-table-tbody > tr > td) {
  background-color: #ffffff !important;
  color: #262626 !important;
  filter: none !important;
}

:deep(.ant-table-tbody > tr:hover > td) {
  background-color: #f5f5f5 !important;
  filter: none !important;
}

:deep(.ant-modal) {
  background-color: #ffffff !important;
  color: #262626 !important;
  filter: none !important;
}

:deep(.ant-modal-content) {
  background-color: #ffffff !important;
  color: #262626 !important;
  filter: none !important;
}

:deep(.ant-form-item-label > label) {
  color: #262626 !important;
  filter: none !important;
}

:deep(.ant-input) {
  background-color: #ffffff !important;
  color: #262626 !important;
  border-color: #d9d9d9 !important;
  filter: none !important;
}

/* Override Dark Reader on all elements */
:deep(*) {
  filter: none !important;
}
</style>
