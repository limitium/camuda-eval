import axios from 'axios';
import { requestEnded, requestStarted } from './progress';

// Injected by the backend at runtime when the app is served under a path prefix.
const BASE_URL = (window.BASE_URL || '/').replace(/\/+/g, '/');
const http = axios.create({ baseURL: BASE_URL });
// Every request shows: the top bar, the button that started it busy, a modal when it changes something.
http.interceptors.request.use(requestStarted);
http.interceptors.response.use(
  (response) => { requestEnded(response.config); return response; },
  (error) => { requestEnded(error?.config); return Promise.reject(error); }
);

/** An API failure in words a person can act on, plus the status for the callers that branch on it. */
export class ApiError extends Error {
  constructor(status, message, body) {
    super(message);
    this.status = status;
    this.body = body;
  }
}

function fail(error) {
  const status = error?.response?.status || 0;
  const body = error?.response?.data;
  const message = (body && typeof body === 'object' && body.error) || error.message || 'request failed';
  return new ApiError(status, message, body);
}

async function call(promise) {
  try {
    return await promise;
  } catch (error) {
    throw fail(error);
  }
}

const wsParams = (ws, extra = {}) => (ws ? { ...extra, ws } : { ...extra });
const enc = encodeURIComponent;

export const api = {
  async app() {
    return (await call(http.get('/api/app'))).data;
  },

  // --- working spaces ---------------------------------------------------------------------------

  async spaces() {
    return (await call(http.get('/api/workspaces'))).data;
  },
  /** Every commit of main and the release lines, newest first, each on its line's lane. */
  async history(days = 0) {
    return (await call(http.get('/api/workspaces/history', { params: days ? { days } : {} }))).data;
  },
  async baselines() {
    return (await call(http.get('/api/workspaces/baselines'))).data;
  },
  async createSpace(name, baseline, on = null) {
    return (await call(http.post('/api/workspaces', { name, baseline, on }))).data;
  },
  async deleteSpace(id) {
    await call(http.delete(`/api/workspaces/${id}`));
  },
  async saveVersion(id, message) {
    return (await call(http.post(`/api/workspaces/${id}/commit`, { message }))).data;
  },
  async changes(id) {
    return (await call(http.get(`/api/workspaces/${id}/changes`))).data.files;
  },
  /** The file as of the space's last saved version, or null when it is new since then. */
  async savedContent(id, path) {
    try {
      return (await http.get(`/api/workspaces/${id}/saved`, { params: { path }, responseType: 'text' })).data;
    } catch (error) {
      if (error?.response?.status === 404) return null;
      throw fail(error);
    }
  },

  // --- tidying old spaces and releases ----------------------------------------------------------

  async cleanupPlan() {
    return (await call(http.get('/api/cleanup'))).data;
  },
  /** items: { [id]: headShown }. Refused whole (409, with a fresh plan) if anything moved. */
  async cleanupApply(items) {
    return (await call(http.post('/api/cleanup/apply', { items }))).data;
  },
  async cleanupRestore(ref) {
    return (await call(http.post('/api/cleanup/restore', { ref }))).data;
  },

  // --- rule and test files ----------------------------------------------------------------------

  async files(ws) {
    return (await call(http.get('/api/dmn', { params: wsParams(ws) }))).data;
  },
  /** Content plus the version a later save must present. */
  async readDmn(ws, file) {
    const res = await call(http.get(`/api/dmn/${enc(file)}.dmn`, { params: wsParams(ws), responseType: 'text' }));
    return { content: res.data || '', etag: res.headers?.etag || null };
  },
  async readTests(ws, file) {
    try {
      const res = await http.get(`/api/yaml/${enc(file)}.yaml`, { params: wsParams(ws), responseType: 'text' });
      return { content: res.data || '', etag: res.headers?.etag || null };
    } catch (error) {
      if (error?.response?.status === 404) return { content: '', etag: null };
      throw fail(error);
    }
  },
  async writeDmn(ws, file, xml, etag) {
    const res = await call(http.put(`/api/dmn/${enc(file)}.dmn`, xml, {
      params: wsParams(ws),
      headers: { 'Content-Type': 'application/xml', ...(etag ? { 'If-Match': etag } : {}) }
    }));
    return { etag: res.headers?.etag || null };
  },
  /** The server stores tests in canonical form and returns it, so the editor shows what is on disk. */
  async writeTests(ws, file, text, etag) {
    const res = await call(http.put(`/api/yaml/${enc(file)}.yaml`, text, {
      params: wsParams(ws),
      headers: { 'Content-Type': 'text/plain', ...(etag ? { 'If-Match': etag } : {}) }
    }));
    return { etag: res.headers?.etag || null, content: res.data?.content ?? text };
  },
  async createFile(ws, file, xml) {
    const form = new FormData();
    form.append('file', new Blob([xml], { type: 'application/xml' }), `${file}.dmn`);
    await call(http.post('/api/dmn', form, { params: wsParams(ws, { name: `${file}.dmn` }) }));
  },
  /** Removes a file only if it is still the version the caller saw (the server refuses otherwise). */
  async deleteFile(ws, file, etag = undefined) {
    const version = etag === undefined ? (await this.readDmn(ws, file)).etag : etag;
    await call(http.delete(`/api/dmn/${enc(file)}.dmn`, {
      params: wsParams(ws),
      headers: version ? { 'If-Match': version } : {}
    }));
  },
  async testHints(ws, file) {
    try {
      return (await http.get(`/api/dmn/${enc(file)}.dmn/test-hints`, { params: wsParams(ws) })).data?.decisions || [];
    } catch {
      return [];
    }
  },
  async testProblems(ws, file) {
    try {
      const data = (await http.get(`/api/dmn/${enc(file)}.dmn/test-problems`, { params: wsParams(ws) })).data;
      return { problems: data?.problems || [], hasErrors: !!data?.hasErrors };
    } catch {
      return { problems: [], hasErrors: false };
    }
  },

  // --- tests ------------------------------------------------------------------------------------

  /** Runs a table's tests against the given content and waits for the verdict. */
  async runTests(file, dmnXml, testsText) {
    const form = new FormData();
    form.append('fileName', file);
    form.append('dmnContent', new Blob([dmnXml], { type: 'application/xml' }), `${file}.dmn`);
    form.append('yamlContent', new Blob([testsText], { type: 'text/plain' }), `${file}.yaml`);
    const started = (await call(http.post('/api/runs', form))).data;
    for (let attempt = 0; attempt < 240; attempt++) {
      await new Promise((resolve) => setTimeout(resolve, attempt < 10 ? 150 : 500));
      const status = (await call(http.get(`/api/runs/${started.id}/status`))).data;
      if (status.state !== 'running') return status;
    }
    throw new ApiError(0, 'the test run did not finish in time');
  },

  // --- diff and merge ---------------------------------------------------------------------------

  async diff(leftXml, rightXml, leftLabel, rightLabel) {
    return (await call(http.post('/api/diff', { leftXml, rightXml, leftLabel, rightLabel }))).data;
  },
  async mergePreview(baseXml, oursXml, theirsXml, resolutions = {}) {
    return (await call(http.post('/api/diff/merge-preview', { baseXml, oursXml, theirsXml, resolutions }))).data;
  },

  // --- applications: the apps this instance controls ----------------------------------------------

  /** { env, allowDraftPublish, applications: [{ name, files, server, ok, running, version, inStep, problems }] } */
  async applications() {
    return (await call(http.get('/api/applications'))).data;
  },
  /** One application: what it runs, and its deployment history read from its topic (newest first). */
  async application(name) {
    return (await call(http.get(`/api/applications/${enc(name)}`))).data;
  },
  /** Every test runs first; refused (TESTS_FAILED with the report, MISSING_FILES, NOT_EXCLUSIVE) otherwise. */
  async deploy(application, release) {
    return (await call(http.post(`/api/applications/${enc(application)}/deploy`, { release }))).data;
  },
  async redeploy(application) {
    return (await call(http.post(`/api/applications/${enc(application)}/redeploy`))).data;
  },
  async restoreRelease(application) {
    return (await call(http.post(`/api/applications/${enc(application)}/restore`))).data;
  },
  /** For a space: every application, with which of the files it reads the space changed. */
  async draftPreview(workspace) {
    return (await call(http.get('/api/applications/draft-preview', { params: { workspace } }))).data;
  },
  async publishDraft(application, workspace) {
    return (await call(http.post(`/api/applications/${enc(application)}/draft`, { workspace, confirm: true }))).data;
  },
  /** Where a new space can start: main, release lines and spaces, each at its newest commit (fetched first). */
  async bases() {
    return (await call(http.get('/api/workspaces/bases'))).data;
  },

  // --- release lines, versions, landing ------------------------------------------------------------

  /** main and every release line: newest commit, versions (verified or not), what releasing would make. */
  async lines() {
    return (await call(http.get('/api/lifecycle/lines'))).data;
  },
  /** Makes the line's next version, only on green tests; with check, only says what it would make. */
  async release(line, notes, check = false, at = null) {
    return (await call(http.post('/api/lifecycle/release', { line, notes, check, at }))).data;
  },
  /** Opens a release line at a commit of main (its newest when no commit); number "M.m" or the next. */
  async openLine(at, number = null) {
    return (await call(http.post('/api/lifecycle/lines/open', { at, number }))).data;
  },
  /** The whole landing chain, computed; with apply (and nothing open, everything green) it lands. */
  /** Carries a release line into every newer line and main, like a landing; with apply, lands it. */
  async carry(line, { resolutions = {}, tips = null, apply = false } = {}) {
    return (await call(http.post('/api/lifecycle/carry', { line, resolutions, tips, apply }))).data;
  },
  async land(id, { resolutions = {}, tips = null, apply = false } = {}) {
    return (await call(http.post(`/api/lifecycle/land/${id}`, { resolutions, tips, apply }))).data;
  },

  /** Brings a line, version or another space (any instance) into a space; with apply it moves the space. */
  async pull(id, from, { resolutions = {}, head = null, apply = false } = {}) {
    return (await call(http.post(`/api/lifecycle/pull/${id}`, { from, resolutions, head, apply }))).data;
  },

  // --- any commit, opened ---------------------------------------------------------------------------

  async commit(rev) {
    return (await call(http.get('/api/commit', { params: { rev } }))).data;
  },
  async commitDomain(rev, name) {
    return (await call(http.get('/api/commit/domain', { params: { rev, name } }))).data;
  },
  /** Every domain's tests at a commit: exactly what a deploy of it runs first. */
  async commitTests(rev) {
    return (await call(http.post('/api/commit/tests', null, { params: { rev } }))).data;
  },

  // --- other instances, through git -----------------------------------------------------------------

  async sync() {
    return (await call(http.get('/api/sync'))).data;
  },
  async syncNow() {
    return (await call(http.post('/api/sync'))).data;
  },
  /** Continues a space on this instance: it gets a checkout here, from its saved version. */
  async openSpace(id) {
    return (await call(http.post(`/api/workspaces/${id}/open`))).data;
  },
  /** Brings this checkout up to the space's newest saved version; unsaved changes are merged and kept. */
  async refresh(id, { resolutions = {}, apply = false } = {}) {
    return (await call(http.post(`/api/lifecycle/refresh/${id}`, { resolutions, apply }))).data;
  },
  async spacesElsewhere() {
    return (await call(http.get('/api/workspaces/elsewhere'))).data;
  },


  // --- trying a table, saved or not ---------------------------------------------------------------

  async evaluate(dmnXml, decision, inputs) {
    return (await call(http.post('/api/evaluate', { dmnXml, decision, inputs }))).data;
  }
};
