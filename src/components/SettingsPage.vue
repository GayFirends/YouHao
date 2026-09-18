<script setup lang="ts">
import { reactive, watch } from 'vue'
import { Check, Cloud, Download, FileJson, RefreshCw, Upload } from '@lucide/vue'
import type { SyncConflict, SyncDeviceState, WebDavConfig } from '../types'

const props = defineProps<{
  config: WebDavConfig
  syncing: boolean
  testing: boolean
  saving: boolean
  lastSync: string
  nativePlatform: boolean
  conflicts: SyncConflict[]
  syncDevices: SyncDeviceState[]
}>()
const emit = defineEmits<{
  save: [config: WebDavConfig]
  test: [config: WebDavConfig]
  sync: []
  export: [format: 'json' | 'csv']
  import: [event: Event]
  diagnostics: []
  resolve: [id: string, resolution: 'local' | 'remote' | 'manual', manual?: string]
}>()
const draft = reactive<WebDavConfig>({ ...props.config })
const manualDrafts = reactive<Record<string, string>>({})
watch(
  () => props.config,
  (value) => Object.assign(draft, value),
)
</script>

<template>
  <section class="page settings-page">
    <div class="page-title">
      <span class="eyebrow">记录随行，安心留存</span>
      <h1>数据与同步</h1>
      <p>连接你的 WebDAV 空间，让不同设备的记录随行。</p>
    </div>
    <div class="settings-layout">
      <form class="panel settings-form" @submit.prevent="emit('save', { ...draft })">
        <div class="section-heading">
          <span class="large-icon"><Cloud :size="24" /></span>
          <div>
            <h2>云端连接</h2>
            <p>支持坚果云、Nextcloud、群晖等 WebDAV 服务</p>
          </div>
        </div>
        <label><span>服务器地址</span><input v-model.trim="draft.url" type="url" placeholder="https://dav.example.com/fuel-track" /></label>
        <div class="form-grid">
          <label><span>用户名</span><input v-model="draft.username" autocomplete="username" placeholder="你的账号" /></label
          ><label
            ><span>应用密码</span
            ><input v-model="draft.password" type="password" autocomplete="current-password" placeholder="WebDAV 应用密码"
          /></label>
        </div>
        <label><span>同步文件名</span><input v-model.trim="draft.fileName" placeholder="fuel-track.json" /></label>
        <label class="encryption-toggle"
          ><input v-model="draft.encryptionEnabled" type="checkbox" /><span>使用独立口令加密云端同步文件</span></label
        >
        <label v-if="draft.encryptionEnabled"
          ><span>同步加密口令 <small>至少 8 个字符，无法找回</small></span
          ><input v-model="draft.encryptionPassphrase" type="password" minlength="8" autocomplete="new-password" required
        /></label>
        <label v-if="draft.encryptionEnabled && nativePlatform" class="encryption-toggle"
          ><input v-model="draft.rememberEncryptionPassphrase" type="checkbox" /><span>使用 Android Keystore 在此设备记住口令</span></label
        >
        <p v-if="draft.encryptionEnabled" class="privacy-note">
          {{
            draft.rememberEncryptionPassphrase && nativePlatform
              ? '口令由 Android Keystore 加密保存在此设备，不会写入数据库、备份或 WebDAV 文件。'
              : '口令只保留在当前会话，不会写入数据库、备份或 WebDAV 文件。'
          }}
        </p>
        <div class="form-actions">
          <button type="button" class="button secondary" :disabled="testing" @click="emit('test', { ...draft })">
            <RefreshCw :size="17" :class="{ spin: testing }" />测试连接</button
          ><button class="button primary"><Check :size="17" />保存设置</button>
        </div>
      </form>
      <aside class="sync-summary">
        <h2>同步状态</h2>
        <div class="sync-illustration"><Cloud :size="32" /><span :class="{ active: lastSync }" /></div>
        <strong>{{ lastSync ? '数据已同步' : '等待首次同步' }}</strong>
        <p>{{ lastSync ? `上次同步：${new Date(lastSync).toLocaleString('zh-CN')}` : '保存连接信息后，点击立即同步。' }}</p>
        <button class="button primary full" :disabled="syncing || !config.url" @click="emit('sync')">
          <RefreshCw :size="18" :class="{ spin: syncing }" />{{ syncing ? '正在合并数据…' : '立即同步' }}
        </button>
        <div class="privacy-note">
          <strong>记录级安全合并</strong><span>同步会比较每条记录的更新时间，多设备离线录入也不会整库覆盖。</span>
        </div>
        <p v-if="syncDevices.length" class="device-summary">
          {{ syncDevices.length }} 台已知设备 · 最早确认
          {{ new Date(Math.min(...syncDevices.map((device) => Date.parse(device.acknowledgedThrough)))).toLocaleString('zh-CN') }}
        </p>
      </aside>
    </div>
    <section v-if="conflicts.length" class="panel conflict-panel">
      <div class="section-heading">
        <div>
          <h2>等待处理的同步冲突</h2>
          <p>本机与云端在上次同步后都修改了同一条数据，请明确选择版本。</p>
        </div>
      </div>
      <article v-for="conflict in conflicts" :key="conflict.id" class="conflict-card">
        <h3>{{ conflict.entityType === 'vehicle' ? '车辆' : '加油记录' }} · {{ conflict.entityId }}</h3>
        <div class="conflict-versions">
          <div>
            <strong>本机版本</strong>
            <pre>{{ JSON.stringify(conflict.localValue, null, 2) }}</pre>
          </div>
          <div>
            <strong>云端版本</strong>
            <pre>{{ JSON.stringify(conflict.remoteValue, null, 2) }}</pre>
          </div>
        </div>
        <div class="form-actions">
          <button class="button secondary" @click="emit('resolve', conflict.id, 'local')">保留本机</button
          ><button class="button secondary" @click="emit('resolve', conflict.id, 'remote')">保留云端</button>
        </div>
        <details>
          <summary>手动合并 JSON</summary>
          <textarea v-model="manualDrafts[conflict.id]" rows="8" :placeholder="JSON.stringify(conflict.localValue, null, 2)" /><button
            class="button primary"
            @click="emit('resolve', conflict.id, 'manual', manualDrafts[conflict.id] || JSON.stringify(conflict.localValue))"
          >
            保存合并结果
          </button>
        </details>
      </article>
    </section>
    <section class="panel backup-panel">
      <div class="section-heading">
        <span class="large-icon"><FileJson :size="24" /></span>
        <div>
          <h2>本地备份</h2>
          <p>导出完整 JSON 备份、CSV 报表，或将 JSON 备份安全合并到当前数据库</p>
        </div>
      </div>
      <div class="backup-actions">
        <button class="button secondary" @click="emit('export', 'json')"><Download :size="17" />导出 JSON</button>
        <button class="button secondary" @click="emit('export', 'csv')"><Download :size="17" />导出 CSV</button>
        <button class="button secondary" @click="emit('diagnostics')"><Download :size="17" />导出脱敏诊断</button>
        <label class="button primary" :class="{ disabled: saving }"
          ><Upload :size="17" />合并导入 JSON<input
            class="visually-hidden"
            type="file"
            accept="application/json,.json"
            :disabled="saving"
            @change="emit('import', $event)"
        /></label>
      </div>
    </section>
  </section>
</template>
