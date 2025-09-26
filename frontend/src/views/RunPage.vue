<template>
  <div class="run-page">
    <a-card :title="`Run Tests: ${baseName}`" :bordered="false">
      <div class="run-controls">
        <a-button type="primary" size="large" @click="startRun" :loading="starting">
          <template #icon>
            <PlayCircleOutlined />
          </template>
          Start Test Run
        </a-button>
        <a-button v-if="run" @click="stopPolling" :disabled="!run || run.state !== 'running'">
          Stop Polling
        </a-button>
      </div>
      
      <a-divider />
      
      <a-tabs v-model:activeKey="activeTab">
        <a-tab-pane key="current" tab="Last Run">
          <div v-if="lastRun">
            <a-descriptions bordered size="small" :column="2">
              <a-descriptions-item label="Run ID">
                <a-typography-text code>{{ lastRun.id }}</a-typography-text>
              </a-descriptions-item>
              <a-descriptions-item label="Status">
                <a-tag :color="getStateColor(lastRun.state, lastRun.result)">
                  {{ getStatusText(lastRun.state, lastRun.result) }}
                </a-tag>
              </a-descriptions-item>
              <a-descriptions-item label="Started">
                {{ formatTimeAgo(lastRun.startedAt) }}
              </a-descriptions-item>
              <a-descriptions-item label="Duration">
                {{ getDuration(lastRun) }}
              </a-descriptions-item>
              <a-descriptions-item label="Test Coverage" v-if="lastRun.coverage">
                {{ lastRun.coverage.testCoveragePercent || lastRun.coverage.coveragePercent || 0 }}%
              </a-descriptions-item>
              <a-descriptions-item label="DMN Coverage" v-if="lastRun.coverage && lastRun.coverage.dmnCoveragePercent !== undefined">
                {{ lastRun.coverage.dmnCoveragePercent || 0 }}%
              </a-descriptions-item>
              <a-descriptions-item label="Tests" v-if="lastRun.coverage">
                {{ lastRun.coverage.passedTests || 0 }}/{{ lastRun.coverage.totalTests || 0 }} passed
              </a-descriptions-item>
              <a-descriptions-item label="DMN Rules" v-if="lastRun.coverage && lastRun.coverage.dmnTotalRules !== undefined">
                {{ lastRun.coverage.dmnCoveredRules || 0 }}/{{ lastRun.coverage.dmnTotalRules || 0 }} covered
              </a-descriptions-item>
            </a-descriptions>
            
            <a-divider />
            
            <a-typography-title :level="5">Test Results Log</a-typography-title>
            <a-spin :spinning="loadingLog">
              <pre class="log-output">{{ lastRunLog || 'Loading log...' }}</pre>
            </a-spin>
          </div>
          <div v-else>
            <a-empty description="No runs yet" />
          </div>
        </a-tab-pane>
        
        <a-tab-pane key="history" tab="Run History">
          <a-table 
            :data-source="previousRuns" 
            :columns="runColumns" 
            row-key="id" 
            :pagination="false"
            size="small"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.dataIndex === 'state'">
                <a-tag :color="getStateColor(record.state, record.result)">
                  {{ getStatusText(record.state, record.result) }}
                </a-tag>
              </template>
              <template v-else-if="column.dataIndex === 'startedAt'">
                {{ formatTimeAgo(record.startedAt) }}
              </template>
              <template v-else-if="column.dataIndex === 'duration'">
                {{ getDuration(record) }}
              </template>
              <template v-else-if="column.dataIndex === 'testCoverage'">
                {{ record.coverage?.testCoveragePercent || record.coverage?.coveragePercent || 0 }}%
              </template>
              <template v-else-if="column.dataIndex === 'dmnCoverage'">
                {{ record.coverage?.dmnCoveragePercent || 0 }}%
              </template>
              <template v-else-if="column.key === 'actions'">
                <a-button size="small" type="link" @click="viewRunDetails(record)">View Details</a-button>
              </template>
            </template>
          </a-table>
        </a-tab-pane>
      </a-tabs>
    </a-card>
    
    <!-- Run Details Modal -->
    <a-modal 
      v-model:open="detailsModalOpen" 
      title="Run Details" 
      width="80%"
      :footer="null"
    >
      <div v-if="selectedRun">
        <a-descriptions bordered size="small" :column="2">
          <a-descriptions-item label="Run ID">
            <a-typography-text code>{{ selectedRun.id }}</a-typography-text>
          </a-descriptions-item>
          <a-descriptions-item label="Status">
            <a-tag :color="getStateColor(selectedRun.state, selectedRun.result)">
              {{ getStatusText(selectedRun.state, selectedRun.result) }}
            </a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="Started">
            {{ formatTimeAgo(selectedRun.startedAt) }}
          </a-descriptions-item>
          <a-descriptions-item label="Duration">
            {{ getDuration(selectedRun) }}
          </a-descriptions-item>
          <a-descriptions-item label="Test Coverage" v-if="selectedRun.coverage">
            {{ selectedRun.coverage.testCoveragePercent || selectedRun.coverage.coveragePercent || 0 }}%
          </a-descriptions-item>
          <a-descriptions-item label="DMN Coverage" v-if="selectedRun.coverage && selectedRun.coverage.dmnCoveragePercent !== undefined">
            {{ selectedRun.coverage.dmnCoveragePercent || 0 }}%
          </a-descriptions-item>
          <a-descriptions-item label="Tests" v-if="selectedRun.coverage">
            {{ selectedRun.coverage.passedTests || 0 }}/{{ selectedRun.coverage.totalTests || 0 }} passed
          </a-descriptions-item>
          <a-descriptions-item label="DMN Rules" v-if="selectedRun.coverage && selectedRun.coverage.dmnTotalRules !== undefined">
            {{ selectedRun.coverage.dmnCoveredRules || 0 }}/{{ selectedRun.coverage.dmnTotalRules || 0 }} covered
          </a-descriptions-item>
        </a-descriptions>
        
        <a-divider />
        
        <a-tabs v-model:activeKey="activeDetailTab" @change="onDetailTabChange">
          <a-tab-pane key="log" tab="Log">
            <pre class="log-output">{{ selectedRunLog || 'Loading...' }}</pre>
          </a-tab-pane>
          <a-tab-pane key="result" tab="Result">
            <pre class="log-output">{{ selectedRunResult || 'Loading...' }}</pre>
          </a-tab-pane>
          <a-tab-pane key="dmn" tab="DMN">
            <pre class="log-output">{{ selectedRunDmn || 'Loading...' }}</pre>
          </a-tab-pane>
          <a-tab-pane key="yaml" tab="YAML">
            <pre class="log-output">{{ selectedRunYaml || 'Loading...' }}</pre>
          </a-tab-pane>
        </a-tabs>
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
import axios from 'axios';
import { useRoute } from 'vue-router';
import { PlayCircleOutlined } from '@ant-design/icons-vue';
import { appStore as store } from '../../src/store';

const route = useRoute();
const name = route.params.name;
const baseName = computed(() => String(name).replace(/\.dmn$/, ''));
store.setBaseName(baseName.value);

// Load file and initialize drafts when component mounts
onMounted(async () => {
  await store.loadFile(baseName.value);
  await loadPreviousRuns();
});

onBeforeUnmount(() => {
  if (timer) clearTimeout(timer);
  if (lastRunTimer) clearTimeout(lastRunTimer);
});

const run = ref(null);
const log = ref('');
const starting = ref(false);
const polling = ref(false);
const activeTab = ref('current');
const previousRuns = ref([]);
const lastRun = ref(null);
const lastRunLog = ref('');
const loadingLog = ref(false);
const detailsModalOpen = ref(false);
const selectedRun = ref(null);
const selectedRunLog = ref('');
const selectedRunResult = ref('');
const selectedRunDmn = ref('');
const selectedRunYaml = ref('');
const activeDetailTab = ref('log');
let timer = null;
let lastRunTimer = null;

const runColumns = [
  { title: 'Run ID', dataIndex: 'id', width: 200 },
  { title: 'Status', dataIndex: 'state', width: 100 },
  { title: 'Started', dataIndex: 'startedAt', width: 120 },
  { title: 'Duration', dataIndex: 'duration', width: 100 },
  { title: 'Test Coverage', dataIndex: 'testCoverage', width: 120 },
  { title: 'DMN Coverage', dataIndex: 'dmnCoverage', width: 120 },
  { title: 'Actions', key: 'actions', width: 100 }
];

async function startRun() {
  starting.value = true;
  try {
    const form = new FormData();
    form.append('fileName', baseName.value);
    
    // Always send inline content from drafts
    const dmnContent = store.getDmnDraft(baseName.value);
    const yamlContent = store.getYamlDraft(baseName.value);
    
    if (dmnContent) {
      form.append('dmnContent', new Blob([dmnContent], { type: 'application/xml' }));
    }
    if (yamlContent) {
      form.append('yamlContent', new Blob([yamlContent], { type: 'text/plain' }));
    }
    
    const { data } = await axios.post('/api/runs', form);
    run.value = data;
    poll();
    await loadPreviousRuns(); // Refresh the runs list and update last run
  } finally {
    starting.value = false;
  }
}

async function poll() {
  if (!run.value) return;
  polling.value = true;
  try {
    const status = await axios.get(`/api/runs/${run.value.id}/status`);
    run.value = status.data;
    
    // Only try to fetch log if the run is finished or we've been polling for a while
    if (run.value.state !== 'running' || (run.value.startedAt && Date.now() - new Date(run.value.startedAt).getTime() > 5000)) {
      try {
        const logRes = await axios.get(`/api/runs/${run.value.id}/log`, { responseType: 'text' });
        log.value = logRes.data || '';
      } catch (e) {
        // Log might not be ready yet - this is normal during async execution
        if (run.value.state === 'running') {
          console.log('Log not ready yet, will retry...');
        } else {
          log.value = 'Log not available';
        }
      }
    }
    
    if (run.value.state === 'running') {
      timer = window.setTimeout(poll, 2000);
    } else {
      timer && window.clearTimeout(timer);
      timer = null;
      // Refresh history and last run when current run completes
      await loadPreviousRuns();
    }
  } catch (e) {
    console.error('Polling error:', e);
    timer && window.clearTimeout(timer);
    timer = null;
  } finally {
    polling.value = false;
  }
}

function stopPolling() {
  if (timer) {
    window.clearTimeout(timer);
    timer = null;
  }
  polling.value = false;
}

function getStateColor(state, result) {
  if (state === 'running') return 'blue';
  if (state === 'failed') return 'red';
  if (state === 'finished') {
    // Check the result field to determine the color
    return result === 'failed' ? 'red' : 'green';
  }
  return 'default';
}

async function loadPreviousRuns() {
  try {
    const { data } = await axios.get(`/api/runs/file/${baseName.value}`);
    previousRuns.value = data;
    
    // Set the last run (most recent)
    if (data && data.length > 0) {
      const newLastRun = data[0]; // Assuming data is sorted by startedAt descending
      
      // Check if the last run has changed or if it's still running
      if (!lastRun.value || lastRun.value.id !== newLastRun.id || newLastRun.state === 'running') {
        lastRun.value = newLastRun;
        await loadLastRunLog();
        
        // If the run is still running, set up polling
        if (newLastRun.state === 'running') {
          pollLastRun();
        }
      }
    } else {
      lastRun.value = null;
      lastRunLog.value = '';
    }
  } catch (error) {
    console.error('Failed to load previous runs:', error);
  }
}

async function loadLastRunLog() {
  if (!lastRun.value) return;
  
  loadingLog.value = true;
  try {
    const { data } = await axios.get(`/api/runs/${lastRun.value.id}/log`, { responseType: 'text' });
    lastRunLog.value = data || '';
  } catch (error) {
    console.error('Failed to load last run log:', error);
    if (error.response?.status === 404) {
      // If the run is still running, retry after a short delay
      if (lastRun.value.state === 'running') {
        lastRunLog.value = 'Log not ready yet...';
        setTimeout(() => {
          if (lastRun.value && lastRun.value.state === 'running') {
            loadLastRunLog();
          }
        }, 2000);
      } else {
        lastRunLog.value = 'Log not available';
      }
    } else {
      lastRunLog.value = 'Log not available';
    }
  } finally {
    loadingLog.value = false;
  }
}

async function pollLastRun() {
  if (!lastRun.value || lastRun.value.state !== 'running') return;
  
  try {
    const { data } = await axios.get(`/api/runs/${lastRun.value.id}/status`);
    lastRun.value = data;
    
    if (data.state === 'running') {
      lastRunTimer = setTimeout(pollLastRun, 2000);
    } else {
      // Run completed, refresh the runs list to get updated status
      await loadPreviousRuns();
    }
  } catch (error) {
    console.error('Failed to poll last run status:', error);
  }
}

function formatDate(dateString) {
  if (!dateString) return '-';
  try {
    return new Date(dateString).toLocaleString();
  } catch {
    return dateString;
  }
}

function formatTimeAgo(dateString) {
  if (!dateString) return '-';
  
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

function getStatusText(state, result) {
  if (state === 'running') return 'Running';
  if (state === 'failed') return 'Failed';
  if (state === 'finished') {
    // Check the result field to determine if it's success or failure
    return result === 'failed' ? 'Failed' : 'Success';
  }
  return state || 'Not started';
}

function getDuration(run) {
  if (!run?.startedAt) return '-';
  
  if (run.state === 'running') {
    const start = new Date(run.startedAt);
    const now = new Date();
    const diffMs = now - start;
    return formatDuration(diffMs);
  }
  
  if (run.finishedAt) {
    const start = new Date(run.startedAt);
    const end = new Date(run.finishedAt);
    const diffMs = end - start;
    return formatDuration(diffMs);
  }
  
  return '-';
}

function formatDuration(milliseconds) {
  const seconds = Math.floor(milliseconds / 1000);
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);
  
  if (hours > 0) {
    return `${hours}h ${minutes % 60}m`;
  } else if (minutes > 0) {
    return `${minutes}m ${seconds % 60}s`;
  } else if (seconds > 0) {
    return `${seconds}s`;
  } else {
    return `${milliseconds}ms`;
  }
}

async function viewRunDetails(runRecord) {
  selectedRun.value = runRecord;
  detailsModalOpen.value = true;
  selectedRunLog.value = 'Loading...';
  selectedRunResult.value = 'Loading...';
  selectedRunDmn.value = 'Click to load DMN content';
  selectedRunYaml.value = 'Click to load YAML content';
  
  try {
    const [logRes, resultRes] = await Promise.all([
      axios.get(`/api/runs/${runRecord.id}/log`).catch(() => ({ data: 'Log not available' })),
      axios.get(`/api/runs/${runRecord.id}/result`).catch(() => ({ data: 'Result not available' }))
    ]);
    
    selectedRunLog.value = logRes.data;
    selectedRunResult.value = resultRes.data;
  } catch (error) {
    console.error('Failed to load run details:', error);
    selectedRunLog.value = 'Error loading log';
    selectedRunResult.value = 'Error loading result';
  }
}

async function loadRunDmn() {
  if (!selectedRun.value || selectedRunDmn.value !== 'Click to load DMN content') return;
  
  selectedRunDmn.value = 'Loading...';
  try {
    const { data } = await axios.get(`/api/runs/${selectedRun.value.id}/dmn`, { responseType: 'text' });
    selectedRunDmn.value = data;
  } catch (error) {
    console.error('Failed to load DMN content:', error);
    selectedRunDmn.value = 'Error loading DMN content';
  }
}

async function loadRunYaml() {
  if (!selectedRun.value || selectedRunYaml.value !== 'Click to load YAML content') return;
  
  selectedRunYaml.value = 'Loading...';
  try {
    const { data } = await axios.get(`/api/runs/${selectedRun.value.id}/yaml`, { responseType: 'text' });
    selectedRunYaml.value = data;
  } catch (error) {
    console.error('Failed to load YAML content:', error);
    selectedRunYaml.value = 'Error loading YAML content';
  }
}

function onDetailTabChange(activeKey) {
  if (activeKey === 'dmn' && selectedRunDmn.value === 'Click to load DMN content') {
    loadRunDmn();
  } else if (activeKey === 'yaml' && selectedRunYaml.value === 'Click to load YAML content') {
    loadRunYaml();
  }
}
</script>

<style scoped>
.run-page {
  height: calc(100vh - 64px);
  background-color: #ffffff !important;
  color: #262626 !important;
  filter: none !important;
}

.run-controls {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
}

.log-output {
  background: #f8f8f8 !important;
  color: #262626 !important;
  padding: 16px;
  min-height: 300px;
  max-height: 500px;
  overflow-y: auto;
  border-radius: 4px;
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: 12px;
  line-height: 1.4;
  white-space: pre-wrap;
  word-wrap: break-word;
  border: 1px solid #d9d9d9;
  filter: none !important;
}

/* Override Dark Reader on log output */
.log-output {
  filter: none !important;
}
</style>
