<template>
  <div class="developer-page">
    <PageHeader
      kicker="DEVELOPER"
      title="开发者管理"
      description="面向开发与运维的内部诊断台。当前提供 Agent 工具清单（与 ChatClient 实际挂载保持一致），后续逐步接入提示词、会话记忆等能力。仅系统管理员可访问。"
    >
      <template #actions>
        <el-button @click="reload">
          <el-icon style="margin-right: 6px"><Refresh /></el-icon>刷新
        </el-button>
      </template>
    </PageHeader>

    <div class="stat-row">
      <StatCard
        icon="Tools"
        label="已注册工具"
        :value="store.tools.length"
        tone="primary"
        sub="ChatClient 当前挂载"
      />
      <StatCard
        icon="View"
        label="只读工具"
        :value="store.readTools.length"
        tone="success"
        sub="查询类，直接执行"
      />
      <StatCard
        icon="EditPen"
        label="写工具"
        :value="store.writeTools.length"
        tone="warning"
        sub="需人工确认后执行"
      />
    </div>

    <el-card shadow="never" class="rc-card">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="Agent 工具清单" name="tools">
          <el-skeleton v-if="store.loading && !store.loaded" :rows="5" animated />
          <el-alert
            v-else-if="store.error"
            type="error"
            :title="store.error"
            :closable="false"
            show-icon
          >
            <el-button size="small" type="primary" plain @click="reload">重试</el-button>
          </el-alert>
          <EmptyBlock
            v-else-if="store.tools.length === 0"
            icon="Tools"
            title="暂无已注册工具"
            description="后端尚未向 ChatClient 挂载任何 @Tool 方法"
          />
          <el-table
            v-else
            v-loading="store.loading"
            :data="pagedTools"
            style="width: 100%"
            row-key="name"
          >
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="tool-detail">
                  <p class="tool-desc">{{ row.description }}</p>
                  <template v-if="row.params.length">
                    <span class="tool-params-title">入参</span>
                    <el-table :data="row.params" size="small" border>
                      <el-table-column prop="name" label="参数" width="170" />
                      <el-table-column prop="type" label="类型" width="90" />
                      <el-table-column label="必填" width="70">
                        <template #default="{ row: param }">
                          <el-tag size="small" :type="param.required ? 'danger' : 'info'" effect="plain">
                            {{ param.required ? '是' : '否' }}
                          </el-tag>
                        </template>
                      </el-table-column>
                      <el-table-column prop="description" label="说明" min-width="260" />
                    </el-table>
                  </template>
                  <el-empty v-else description="该工具无入参" :image-size="56" />
                </div>
              </template>
            </el-table-column>
            <el-table-column label="工具名" min-width="230">
              <template #default="{ row }">
                <code class="tool-name">{{ row.name }}</code>
              </template>
            </el-table-column>
            <el-table-column label="类别" width="120">
              <template #default="{ row }">
                <el-tag size="small" :type="row.category === 'WRITE' ? 'warning' : 'success'" effect="light">
                  {{ row.category === 'WRITE' ? '写 · 需确认' : '只读' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" type="info" effect="plain">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="入参" width="70">
              <template #default="{ row }">
                <span class="num">{{ row.params.length }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="description" label="描述" min-width="280" show-overflow-tooltip />
          </el-table>
          <div class="tools-pagination">
            <el-pagination
              v-model:current-page="toolPage"
              v-model:page-size="toolPageSize"
              :total="store.tools.length"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next"
              background
              size="small"
            />
          </div>
        </el-tab-pane>
        <el-tab-pane label="语义记忆检索" name="memory">
          <div class="memory-panel">
            <el-alert
              type="info"
              :closable="false"
              show-icon
              title="pgvector 混合相关性召回"
            >
              <p class="memory-note">
                跨会话语义记忆已从「最新 N 条全量注入」升级为「按当前问题相关性召回」。
                该能力由后端配置 <code>rcdis.agent.memory.semantic-retrieval-enabled</code> 控制，默认关闭；
                关闭时行为与升级前完全一致。开启前需确保数据库已启用 pgvector / pg_trgm 扩展、并配置了 embedding 模型。
              </p>
              <p class="memory-note">
                升级后新抽取的记忆会自动生成向量；<b>历史遗留记忆</b>需点下方按钮补齐 embedding（仅开启时生效，逐行调用模型、有界执行）。
              </p>
            </el-alert>
            <div class="memory-actions">
              <el-input-number v-model="backfillLimit" :min="1" :max="500" size="small" />
              <el-button
                type="primary"
                plain
                :loading="store.backfilling"
                @click="runBackfill"
              >
                回填缺失的 embedding
              </el-button>
            </div>

            <el-divider content-position="left">召回探针（只读，不影响线上注入）</el-divider>

            <p class="memory-note">
              输入一个问题，查看向量通道与关键词通道各自召回了哪些记忆、余弦距离 / 相似度与融合名次，
              用于评估 Embedding 效果与调优混合权重。<b>即使总开关未开也会强制预览</b>。
            </p>
            <div class="probe-form">
              <el-input
                v-model="probeQuery"
                placeholder="输入要测试的问题，例如：我的报销抬头用什么？"
                style="flex: 1; min-width: 240px"
                @keyup.enter="runProbe"
              />
              <el-input v-model="probeUserId" placeholder="userId（留空=当前用户）" style="width: 200px" />
              <el-input-number v-model="probeTopK" :min="1" :max="20" size="default" />
              <el-button type="primary" :loading="store.probing" @click="runProbe">探测</el-button>
            </div>

            <el-alert
              v-if="store.probeError"
              type="error"
              :title="store.probeError"
              :closable="false"
              show-icon
              style="margin-top: 12px"
            />

            <template v-if="result">
              <div class="probe-meta">
                <el-tag size="small" :type="modeTagType(result.mode)" effect="light">模式：{{ result.mode }}</el-tag>
                <el-tag size="small" :type="result.vectorAvailable ? 'success' : 'info'" effect="plain">
                  向量通道 {{ result.vectorAvailable ? '可用' : '不可用' }}
                </el-tag>
                <el-tag size="small" :type="result.keywordAvailable ? 'success' : 'info'" effect="plain">
                  关键词通道 {{ result.keywordAvailable ? '可用' : '不可用' }}
                </el-tag>
                <span class="probe-meta-text">
                  供应商 {{ result.embeddingProvider }} · 模型 {{ result.embeddingModel }} · 维度 {{ result.dimension }} · α={{ result.alpha }}
                </span>
                <el-tag size="small" :type="gateDisabled(result.vectorMaxDistance) ? 'info' : 'primary'" effect="plain">
                  距离阈值 {{ gateDisabled(result.vectorMaxDistance) ? '已关闭' : result.vectorMaxDistance.toFixed(3) }}
                </el-tag>
              </div>
              <el-alert
                v-if="result.note"
                type="warning"
                :closable="false"
                :title="result.note"
                show-icon
                style="margin: 10px 0"
              />
              <el-table :data="result.hits" size="small" style="width: 100%; margin-top: 8px" empty-text="未召回到记忆">
                <el-table-column prop="fusedRank" label="#" width="48" />
                <el-table-column prop="factType" label="类型" width="110" />
                <el-table-column prop="content" label="记忆内容" min-width="260" show-overflow-tooltip />
                <el-table-column label="余弦距离" width="110">
                  <template #default="{ row }">
                    <span class="num">{{ fmt(row.cosineDistance) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="关键词分" width="110">
                  <template #default="{ row }">
                    <span class="num">{{ fmt(row.keywordScore) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="线上会注入" width="110">
                  <template #default="{ row }">
                    <el-tag size="small" :type="thresholdTagType(row.withinThreshold)" effect="plain">
                      {{ thresholdLabel(row.withinThreshold) }}
                    </el-tag>
                  </template>
                </el-table-column>
              </el-table>
            </template>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

import { ElMessage } from 'element-plus'

import EmptyBlock from '@/components/EmptyBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatCard from '@/components/StatCard.vue'
import { useDeveloperStore } from '@/stores/developer'
const store = useDeveloperStore()
const activeTab = ref('tools')

function reload() {
  void store.load(true)
}

onMounted(() => {
  void store.load()
})
// ---------- Agent 工具清单分页（客户端分页：清单一次全量拉取） ----------
const toolPage = ref(1)
const toolPageSize = ref(10)
const pagedTools = computed(() =>
  store.tools.slice((toolPage.value - 1) * toolPageSize.value, toolPage.value * toolPageSize.value)
)
watch([toolPageSize], () => {
  toolPage.value = 1
})

// ---------- 语义记忆 embedding 回填 ----------
const backfillLimit = ref(50)

async function runBackfill() {
  try {
    const embedded = await store.backfillEmbeddings(backfillLimit.value)
    if (embedded > 0) {
      ElMessage.success(`已为 ${embedded} 条历史记忆生成 embedding`)
    } else {
      ElMessage.info('没有需要回填的记忆（可能未开启语义检索，或向量已是最新）')
    }
  } catch (error) {
    ElMessage.error((error as Error)?.message || '回填失败，请稍后重试')
  }
}

// ---------- 语义记忆召回探针 ----------
const probeQuery = ref('')
const probeUserId = ref('')
const probeTopK = ref(5)
const result = computed(() => store.probeResult)

async function runProbe() {
  const query = probeQuery.value.trim()
  if (!query) {
    ElMessage.warning('请输入要测试的问题')
    return
  }
  await store.runProbe({
    userId: probeUserId.value.trim() || undefined,
    query,
    topK: probeTopK.value
  })
  if (!store.probeError && result.value && result.value.hits.length === 0) {
    ElMessage.info('未召回到记忆，参考提示排查（回填 embedding / 维度 / 扩展）')
  }
}

function fmt(value: number | null): string {
  return value == null ? '—' : value.toFixed(4)
}

/** 余弦距离上限为 2，等于 2 意味着“全部放行”，即阈值已关闭。 */
function gateDisabled(maxDistance: number): boolean {
  return maxDistance >= 2
}

function thresholdTagType(within: boolean | null): 'success' | 'danger' | 'info' {
  if (within == null) return 'info'
  return within ? 'success' : 'danger'
}

function thresholdLabel(within: boolean | null): string {
  if (within == null) return '不适用'
  return within ? '会' : '被滤除'
}

function modeTagType(mode: string): 'success' | 'warning' | 'info' {
  if (mode === 'hybrid') return 'success'
  if (mode === 'no_match') return 'info'
  return 'warning'
}
</script>

<style scoped lang="scss">
.stat-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

// 该页三个指标卡是轻量诊断信息，比 Dashboard 的主指标卡小一号。
.stat-row :deep(.stat-card) {
  padding: 12px 16px;
  gap: 10px;
  align-items: center;

  &:hover {
    transform: none;
  }
}

.stat-row :deep(.stat-icon) {
  width: 32px;
  height: 32px;
  border-radius: 8px;
}

.stat-row :deep(.stat-value-row) {
  margin-top: 2px;
}

.stat-row :deep(.stat-value) {
  font-size: 16px;
}

.stat-row :deep(.stat-sub) {
  margin-top: 2px;
  font-size: 11px;
}

.tools-pagination {
  display: flex;
  justify-content: flex-end;
  padding-top: 12px;
}

.memory-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 760px;
}

.memory-note {
  margin: 6px 0 0;
  line-height: 1.7;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.memory-note code {
  padding: 1px 5px;
  border-radius: 4px;
  background: #eef1f8;
  font-size: 12px;
}

.memory-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.probe-form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
}

.probe-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}

.probe-meta-text {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.tool-name {
  padding: 2px 7px;
  border-radius: 5px;
  background: #eef1f8;
  color: var(--rc-primary-strong);
  font-size: 12.5px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
}

.tool-detail {
  padding: 4px 48px 16px;
}

.tool-desc {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--rc-text-secondary);
  white-space: pre-wrap;
}

.tool-params-title {
  display: block;
  margin-bottom: 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--rc-text-muted);
}

@media (max-width: 900px) {
  .stat-row {
    grid-template-columns: 1fr;
  }
}
</style>
