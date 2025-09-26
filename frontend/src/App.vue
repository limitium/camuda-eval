<template>
  <a-config-provider :theme="{ algorithm: antdTheme.defaultAlgorithm, token: { colorBgContainer: '#ffffff', colorBgElevated: '#ffffff' } }">
    <a-layout style="min-height: 100vh">
      <a-layout-header style="background: #fff; border-bottom: 1px solid #f0f0f0;">
        <div style="display: flex; align-items: center; justify-content: space-between;">
          <div style="display:flex; align-items:center; gap:16px;">
            <a-menu
              mode="horizontal"
              :selected-keys="leftSelectedKeys"
              style="background: transparent; border: none;"
              @click="handleLeftMenuClick"
            >
              <a-menu-item key="list">
                <template #icon>
                  <UnorderedListOutlined />
                </template>
                Files
              </a-menu-item>
            </a-menu>
          </div>
          <div style="display:flex; align-items:center; gap:12px;">
            
          <a-menu
            v-if="showRightNavigation"
            mode="horizontal"
            :selected-keys="selectedKeys"
            style="background: transparent; border: none;"
            @click="handleMenuClick"
          >
            <a-menu-item key="dmn">
              <template #icon>
                <FileTextOutlined />
              </template>
              DMN
            </a-menu-item>
            <a-menu-item key="test">
              <template #icon>
                <CodeOutlined />
              </template>
              Test
            </a-menu-item>
            <a-menu-item key="run">
              <template #icon>
                <PlayCircleOutlined />
              </template>
              Run
            </a-menu-item>
          </a-menu>
          <a-button v-if="showRightNavigation" type="primary" :loading="store.saving" :disabled="store.saving" @click="store.saveAll">Save changes</a-button>
          </div>
        </div>
      </a-layout-header>
      <a-alert
        v-if="store.error"
        type="error"
        :message="store.error"
        closable
        @close="store.clearError"
        style="margin: 0; border-radius: 0;"
      />
      <a-layout-content>
        <router-view />
      </a-layout-content>
    </a-layout>
  </a-config-provider>
</template>

<script setup>
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { theme as antdTheme } from 'ant-design-vue';
import { FileTextOutlined, CodeOutlined, PlayCircleOutlined, UnorderedListOutlined } from '@ant-design/icons-vue';
import { appStore as store } from './store';

const route = useRoute();
const router = useRouter();

const isListPage = computed(() => route.path === '/');

const showRightNavigation = computed(() => {
  return !!route.params.name && !isListPage.value;
});

const leftSelectedKeys = computed(() => (isListPage.value ? ['list'] : []));

const selectedKeys = computed(() => {
  if (route.path.includes('/dmn/')) return ['dmn'];
  if (route.path.includes('/test/')) return ['test'];
  if (route.path.includes('/run/')) return ['run'];
  return [];
});

const handleLeftMenuClick = ({ key }) => {
  if (key === 'list') {
    router.push('/');
  }
};

const handleMenuClick = ({ key }) => {
  const name = route.params.name;
  if (key === 'list') {
    router.push('/');
    return;
  }
  if (name) {
    router.push(`/${key}/${name}`);
  }
};
</script>

<style>
html, body, #app { 
  height: 100%; 
  background-color: #ffffff !important;
  color: #262626 !important;
  filter: none !important;
}

/* Prevent dark reader from overriding */
html[data-darkreader-scheme="dark"] {
  background-color: #ffffff !important;
  color: #262626 !important;
  filter: none !important;
}

html[data-darkreader-scheme="dark"] body {
  background-color: #ffffff !important;
  color: #262626 !important;
  filter: none !important;
}

html[data-darkreader-scheme="dark"] #app {
  background-color: #ffffff !important;
  color: #262626 !important;
  filter: none !important;
}

/* Force light theme on all Ant Design components */
.ant-layout {
  background-color: #ffffff !important;
  color: #262626 !important;
  filter: none !important;
}

.ant-layout-header {
  background-color: #ffffff !important;
  color: #262626 !important;
  filter: none !important;
}

.ant-layout-content {
  background-color: #ffffff !important;
  color: #262626 !important;
  filter: none !important;
}

.ant-card {
  background-color: #ffffff !important;
  color: #262626 !important;
  filter: none !important;
}

/* Menu styling for light theme */
.ant-menu {
  background-color: transparent !important;
  color: #262626 !important;
  filter: none !important;
}

.ant-menu-item {
  color: #262626 !important;
  filter: none !important;
}

.ant-menu-item:hover {
  color: #1890ff !important;
  background-color: #f5f5f5 !important;
  filter: none !important;
}

.ant-menu-item-selected {
  color: #1890ff !important;
  background-color: #e6f7ff !important;
  filter: none !important;
}

/* Typography styling */
.ant-typography {
  color: #262626 !important;
  filter: none !important;
}

/* Override any Dark Reader filters on all elements */
* {
  filter: none !important;
}

/* Specific overrides for Dark Reader attributes */
[data-darkreader-scheme="dark"] * {
  filter: none !important;
  background-color: inherit !important;
  color: inherit !important;
}

[data-darkreader-mode="dynamic"] * {
  filter: none !important;
}

[data-darkreader-inverted] * {
  filter: none !important;
}
</style>


