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
        sub="需二次确认（第二期接入）"
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
            :data="store.tools"
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
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'

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
</script>

<style scoped lang="scss">
.stat-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 16px;
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
