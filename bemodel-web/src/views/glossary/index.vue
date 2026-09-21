<template>
  <div class="page">
    <!-- 语义搜索 -->
    <el-card>
      <div class="search-bar">
        <el-input
          v-model="q"
          size="large"
          placeholder="用自然语言查询业务口径，如：出院人数怎么算 / 病员是什么"
          clearable
          @keyup.enter="doSearch"
        >
          <template #append>
            <el-button type="primary" :loading="searching" @click="doSearch">搜索</el-button>
          </template>
        </el-input>
      </div>
      <div v-if="searched" class="search-result">
        <el-alert v-if="answer" type="success" :closable="false" class="answer-alert">
          <template #title>
            <div class="answer-title">
              <span>{{ answer }}</span>
              <el-tag size="small" effect="plain" type="success">AI 生成</el-tag>
            </div>
          </template>
        </el-alert>
        <el-table :data="hits" style="margin-top: 12px" size="small">
          <el-table-column label="类型" width="90">
            <template #default="{ row }">
              <el-tag :type="hitTagType(row.type)">{{ row.type }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="title" label="标题" width="180" />
          <el-table-column prop="conceptCode" label="概念" width="140" />
          <el-table-column prop="content" label="内容" show-overflow-tooltip />
        </el-table>
        <el-empty v-if="!hits.length && !answer" description="未找到相关口径" :image-size="60" />
      </div>
    </el-card>

    <!-- 术语库 / 指标库 -->
    <el-card style="margin-top: 16px">
      <el-tabs v-model="tab">
        <el-tab-pane label="术语库" name="terms">
          <div class="filter-bar">
            <el-select
              v-model="termConcept"
              placeholder="按概念筛选"
              clearable
              filterable
              style="width: 240px"
              @change="loadTerms"
            >
              <el-option
                v-for="c in conceptStore.concepts"
                :key="c.code"
                :label="`${c.name}（${c.code}）`"
                :value="c.code"
              />
            </el-select>
            <el-select v-model="codeSystemFilter" style="width: 160px" @change="termPage = 1">
              <el-option label="全部体系" value="" />
              <el-option label="SNOMED CT" value="SNOMED CT" />
              <el-option label="ICD-10" value="ICD-10" />
              <el-option label="平台标准" value="平台标准" />
              <el-option label="产品方言" value="产品方言" />
            </el-select>
            <el-button type="primary" plain @click="openTermDialog">新增术语</el-button>
          </div>
          <el-table :data="pagedTerms" v-loading="loadingTerms">
            <el-table-column prop="term" label="术语" width="150" />
            <el-table-column prop="sourceProduct" label="来源产品" width="130" />
            <el-table-column label="类型" width="90">
              <template #default="{ row }">
                <el-tag :type="row.termType === 'STANDARD' ? 'success' : 'info'">
                  {{ row.termType === 'STANDARD' ? '标准' : '别名' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="编码体系" width="120">
              <template #default="{ row }">
                <el-tag size="small" :type="codeSystemTag(row)">{{ row.codeSystem || row.sourceProduct }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="标准编码" width="130">
              <template #default="{ row }">
                <span class="std-code">{{ row.standardCode || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="conceptCode" label="所属概念" />
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button size="small" type="danger" link @click="removeTerm(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            v-if="filteredTerms.length > 0"
            class="term-pager"
            small
            layout="total, prev, pager, next"
            v-model:current-page="termPage"
            :page-size="termPageSize"
            :total="filteredTerms.length"
          />
        </el-tab-pane>
        <el-tab-pane label="指标库" name="metrics">
          <div class="metric-header">
            <span class="metric-header-title">指标库</span>
            <el-button type="primary" :loading="inspecting" @click="inspectAll">全量巡检</el-button>
          </div>

          <!-- 指标统计头（全部来自真实台账与巡检结果） -->
          <div class="metric-stats">
            <div class="mstat">
              <div class="mstat-value">{{ metricStats.total }}</div>
              <div class="mstat-label">已定义指标</div>
            </div>
            <div class="mstat">
              <div class="mstat-value" style="color: var(--el-color-success)">
                {{ metricStats.monitored }}
              </div>
              <div class="mstat-label">已接入巡检</div>
            </div>
            <div class="mstat">
              <div class="mstat-value" :class="{ 'mstat-warn': metricStats.alarmed }">
                {{ metricStats.alarmed }}
              </div>
              <div class="mstat-label">巡检告警中</div>
            </div>
            <div class="mstat">
              <div class="mstat-value" style="color: #8b5cf6">{{ metricStats.newThisMonth }}</div>
              <div class="mstat-label">本月新增</div>
            </div>
          </div>

          <!-- 筛选工具栏 -->
          <div class="metric-toolbar">
            <el-input
              v-model="metricKw"
              placeholder="搜索指标名称 / 编码"
              clearable
              :prefix-icon="Search"
              style="width: 220px"
            />
            <el-select v-model="metricDomain" placeholder="所有业务域" clearable style="width: 160px">
              <el-option v-for="d in metricDomains" :key="d" :label="d" :value="d" />
            </el-select>
            <el-select
              v-model="metricStatus"
              placeholder="所有监控状态"
              clearable
              style="width: 150px"
            >
              <el-option label="告警中" value="alarm" />
              <el-option label="正常" value="ok" />
              <el-option label="未接入监控" value="none" />
            </el-select>
            <span class="metric-toolbar-tip">
              {{ filteredMetrics.length }} / {{ metrics.length }} 项 · 状态来自巡检实测，非人工标注
            </span>
          </div>

          <el-alert
            v-if="inspected && alarmedMetrics.length"
            type="error"
            :closable="false"
            class="alarm-alert"
            :title="`存在 ${alarmedMetrics.length} 项指标告警：${alarmedMetrics.map((x) => x.name).join('、')}`"
          />
          <el-row :gutter="16" v-loading="loadingMetrics">
            <el-col v-for="m in pagedMetrics" :key="m.id" :span="8">
              <el-card
                :id="`metric-${m.metricCode}`"
                class="metric-card"
                :class="{ 'metric-highlight': m.metricCode === highlightCode }"
                shadow="hover"
              >
                <div class="metric-name">
                  {{ m.name }}
                  <span class="metric-code">{{ m.metricCode }}</span>
                  <el-tag v-if="metricDomainOf(m)" size="small" effect="plain" style="margin-left: 6px">
                    {{ metricDomainOf(m) }}
                  </el-tag>
                  <el-tag
                    v-if="!hasProbe(m)"
                    size="small"
                    type="info"
                    effect="plain"
                    style="margin-left: 6px"
                  >未接入监控</el-tag>
                </div>
                <div class="metric-def">{{ m.definition }}</div>
                <pre class="metric-formula">{{ m.formula }}</pre>
                <div v-if="hasProbe(m)" class="metric-monitor">
                  <template v-if="displayValue(m) !== null">
                    <span class="metric-value" :class="{ alarm: displayAlarm(m) }">
                      {{ displayValue(m) }}
                    </span>
                    <el-tag size="small" :type="displayAlarm(m) ? 'danger' : 'success'">
                      {{ displayAlarm(m) ? '告警' : '正常' }}
                    </el-tag>
                    <span class="metric-time">{{ displayTime(m) }}</span>
                  </template>
                  <span v-else class="metric-no-val">暂无实测值</span>
                </div>
                <div class="metric-owner">
                  <span>
                    负责人：{{ m.owner || '-' }}
                    <el-tag size="small" effect="plain" style="margin-left: 8px">{{ m.conceptCode }}</el-tag>
                    <span v-if="m.warnThreshold != null" class="metric-threshold">
                      阈值 {{ m.warnThreshold }}
                    </span>
                  </span>
                  <el-button
                    v-if="hasProbe(m)"
                    size="small"
                    type="primary"
                    plain
                    :loading="evaluatingCode === m.metricCode"
                    @click="runEvaluate(m)"
                  >执行检测</el-button>
                </div>
              </el-card>
            </el-col>
          </el-row>
          <el-pagination
            v-if="filteredMetrics.length > metricPageSize"
            class="metric-pager"
            small
            layout="total, prev, pager, next"
            v-model:current-page="metricPage"
            :page-size="metricPageSize"
            :total="filteredMetrics.length"
          />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 新增术语 -->
    <el-dialog v-model="termDialogVisible" title="新增术语" width="480px">
      <el-form :model="termForm" label-width="90px">
        <el-form-item label="术语" required>
          <el-input v-model="termForm.term" />
        </el-form-item>
        <el-form-item label="所属概念" required>
          <el-select v-model="termForm.conceptCode" filterable style="width: 100%">
            <el-option
              v-for="c in conceptStore.concepts"
              :key="c.code"
              :label="`${c.name}（${c.code}）`"
              :value="c.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="来源产品">
          <el-input v-model="termForm.sourceProduct" placeholder="如 HIS / LIS / 平台标准" />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="termForm.termType">
            <el-radio value="STANDARD">标准</el-radio>
            <el-radio value="ALIAS">别名</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="termDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingTerm" @click="submitTerm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox, ElLoading } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import {
  listTerms,
  createTerm,
  deleteTerm,
  listMetrics,
  searchGlossary,
  evaluateMetric,
  evaluateAllMetrics
} from '../../api/glossary'
import { useConceptStore } from '../../store/concept'

const conceptStore = useConceptStore()
const route = useRoute()

// ---------- 语义搜索 ----------
const q = ref('')
const searching = ref(false)
const searched = ref(false)
const answer = ref('')
const hits = ref([])

const hitTagType = (type) =>
  ({ 术语: 'info', 概念: 'primary', 指标: 'warning' }[type] || 'info')

const doSearch = async () => {
  if (!q.value.trim()) {
    ElMessage.warning('请输入查询内容')
    return
  }
  searching.value = true
  try {
    const res = await searchGlossary(q.value.trim())
    searched.value = true
    answer.value = res.answer || ''
    hits.value = res.hits || []
  } finally {
    searching.value = false
  }
}

// ---------- 术语库 ----------
const tab = ref('terms')
const terms = ref([])
const termConcept = ref('')
const loadingTerms = ref(false)
const termPage = ref(1)
const termPageSize = 20

const loadTerms = async () => {
  loadingTerms.value = true
  try {
    terms.value = await listTerms(termConcept.value || undefined)
    termPage.value = 1
  } finally {
    loadingTerms.value = false
  }
}

// ---------- 编码体系筛选（本地过滤） ----------
const STANDARD_SYSTEMS = ['平台标准', 'SNOMED CT', 'ICD-10']
const codeSystemFilter = ref('')

const codeSystemTag = (row) => {
  const sys = row.codeSystem || row.sourceProduct
  if (sys === 'SNOMED CT') return 'primary'
  if (sys === 'ICD-10') return 'success'
  if (sys === '平台标准') return 'info'
  return 'warning'
}

// 产品方言：sourceProduct 不属于三大标准体系
const filteredTerms = computed(() => {
  if (!codeSystemFilter.value) return terms.value
  if (codeSystemFilter.value === '产品方言') {
    return terms.value.filter((t) => !STANDARD_SYSTEMS.includes(t.sourceProduct))
  }
  return terms.value.filter((t) => t.sourceProduct === codeSystemFilter.value)
})

const pagedTerms = computed(() => {
  const start = (termPage.value - 1) * termPageSize
  return filteredTerms.value.slice(start, start + termPageSize)
})

const termDialogVisible = ref(false)
const savingTerm = ref(false)
const termForm = reactive({ term: '', conceptCode: '', sourceProduct: '', termType: 'ALIAS' })

const openTermDialog = () => {
  Object.assign(termForm, {
    term: '',
    conceptCode: termConcept.value || '',
    sourceProduct: '',
    termType: 'ALIAS'
  })
  termDialogVisible.value = true
}

const submitTerm = async () => {
  if (!termForm.term || !termForm.conceptCode) {
    ElMessage.warning('请填写术语和所属概念')
    return
  }
  savingTerm.value = true
  try {
    await createTerm({ ...termForm })
    ElMessage.success('术语已保存')
    termDialogVisible.value = false
    loadTerms()
  } finally {
    savingTerm.value = false
  }
}

const removeTerm = async (row) => {
  await ElMessageBox.confirm(`确认删除术语「${row.term}」？`, '提示', { type: 'warning' })
  await deleteTerm(row.id)
  ElMessage.success('已删除')
  loadTerms()
}

// ---------- 指标库 ----------
const metrics = ref([])
const loadingMetrics = ref(false)
const metricPage = ref(1)
const metricPageSize = 12
const highlightCode = ref('') // 深链定位的指标（口径卡「去统一口径页」跳入时高亮）

// 筛选：关键字 / 业务域（来自绑定概念）/ 监控状态（来自巡检实测）
const metricKw = ref('')
const metricDomain = ref('')
const metricStatus = ref('')

// 指标 → 绑定概念 → 业务域；概念未加载或未绑定时返回空
const metricDomainOf = (m) => {
  const c = conceptStore.concepts.find((x) => x.code === m.conceptCode)
  return c?.domainCode || ''
}

const metricDomains = computed(() => {
  const set = new Set(metrics.value.map(metricDomainOf).filter(Boolean))
  return [...set]
})

const metricStats = computed(() => {
  const monthPrefix = new Date().toISOString().slice(0, 7)
  return {
    total: metrics.value.length,
    monitored: metrics.value.filter(hasProbe).length,
    alarmed: metrics.value.filter((m) => displayAlarm(m)).length,
    newThisMonth: metrics.value.filter(
      (m) => (m.createdAt || '').startsWith(monthPrefix)
    ).length
  }
})

const filteredMetrics = computed(() => {
  let list = metrics.value
  const kw = metricKw.value.trim().toLowerCase()
  if (kw) {
    list = list.filter(
      (m) => m.name?.toLowerCase().includes(kw) || m.metricCode?.toLowerCase().includes(kw)
    )
  }
  if (metricDomain.value) {
    list = list.filter((m) => metricDomainOf(m) === metricDomain.value)
  }
  if (metricStatus.value === 'alarm') {
    list = list.filter((m) => displayAlarm(m))
  } else if (metricStatus.value === 'ok') {
    list = list.filter((m) => hasProbe(m) && !displayAlarm(m))
  } else if (metricStatus.value === 'none') {
    list = list.filter((m) => !hasProbe(m))
  }
  return list
})

const pagedMetrics = computed(() => {
  const start = (metricPage.value - 1) * metricPageSize
  return filteredMetrics.value.slice(start, start + metricPageSize)
})

watch([metricKw, metricDomain, metricStatus], () => {
  metricPage.value = 1
})

const loadMetrics = async () => {
  loadingMetrics.value = true
  try {
    metrics.value = await listMetrics()
    metricPage.value = 1
  } finally {
    loadingMetrics.value = false
  }
}

// ---------- 指标监控 ----------
const inspecting = ref(false)
const inspected = ref(false)
const evaluatingCode = ref('')
const evalMap = ref({})

const hasProbe = (m) => !!(m.dsCode && m.probeSql)

const displayValue = (m) => {
  const ev = evalMap.value[m.metricCode]
  if (ev) return ev.value
  return m.lastVal ?? null
}

const displayTime = (m) => {
  const ev = evalMap.value[m.metricCode]
  if (ev) return ev.evaluatedAt
  return m.lastEvalAt || ''
}

const displayAlarm = (m) => {
  const ev = evalMap.value[m.metricCode]
  if (ev) return ev.alarm
  return m.warnThreshold != null && m.lastVal != null && m.lastVal > m.warnThreshold
}

const alarmedMetrics = computed(() => metrics.value.filter((m) => displayAlarm(m)))

const inspectAll = async () => {
  inspecting.value = true
  const loadingInstance = ElLoading.service({
    text: '正在执行实测探针...',
    background: 'rgba(255, 255, 255, 0.7)'
  })
  try {
    const results = await evaluateAllMetrics()
    const map = { ...evalMap.value }
    for (const r of results || []) {
      map[r.metricCode] = r
    }
    evalMap.value = map
    inspected.value = true
    const alarms = (results || []).filter((r) => r.alarm)
    if (alarms.length) {
      ElMessage.warning(`巡检完成：${alarms.length} 项指标告警`)
    } else {
      ElMessage.success('巡检完成：全部指标正常')
    }
  } finally {
    loadingInstance.close()
    inspecting.value = false
  }
}

const runEvaluate = async (m) => {
  evaluatingCode.value = m.metricCode
  try {
    const res = await evaluateMetric(m.metricCode)
    evalMap.value = { ...evalMap.value, [res.metricCode]: res }
    if (res.alarm) {
      ElMessage.warning(`${m.name} 实测值 ${res.value}，触发告警（阈值 ${res.warnThreshold}）`)
    } else {
      ElMessage.success(`${m.name} 实测值 ${res.value}，正常`)
    }
  } finally {
    evaluatingCode.value = ''
  }
}

// 承接问数页口径卡深链（/glossary?metric=XXX）：切到指标库、清筛选、翻页并高亮定位；
// 找不到时如实告知，不静默失败
const locateMetric = (code) => {
  if (!metrics.value.length) {
    ElMessage.info('指标列表未加载，无法定位指标')
    return
  }
  if (!metrics.value.some((m) => m.metricCode === code)) {
    ElMessage.info(`未在指标库找到指标 ${code}，可能已被删除`)
    return
  }
  highlightCode.value = code
  tab.value = 'metrics'
  metricKw.value = ''
  metricDomain.value = ''
  metricStatus.value = ''
  const idx = filteredMetrics.value.findIndex((m) => m.metricCode === code)
  if (idx >= 0) metricPage.value = Math.floor(idx / metricPageSize) + 1
  nextTick(() => {
    document.getElementById(`metric-${code}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  })
}

onMounted(async () => {
  conceptStore.fetchAll()
  loadTerms()
  try {
    await loadMetrics()
  } catch (e) {
    // 列表加载失败保持页面空态；深链定位会提示「未加载」
  }
  const code = route.query.metric
  if (code) locateMetric(String(code))
})
</script>

<style scoped>
.search-bar {
  max-width: 760px;
}

.answer-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.filter-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}

.term-pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.metric-pager {
  justify-content: flex-end;
}

.std-code {
  font-family: 'SFMono-Regular', Consolas, Menlo, monospace;
  font-size: 12px;
}

.metric-card {
  margin-bottom: 16px;
}

.metric-card.metric-highlight {
  border-color: var(--primary);
  box-shadow: 0 0 0 2px var(--primary-light, rgba(45, 138, 126, 0.25));
}

.metric-name {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 8px;
}

.metric-code {
  font-size: 12px;
  color: #909399;
  font-weight: 400;
  margin-left: 6px;
}

.metric-def {
  font-size: 13px;
  color: #606266;
  margin-bottom: 8px;
  min-height: 40px;
}

.metric-formula {
  margin: 0 0 8px;
  padding: 8px 10px;
  background: #f5f7fa;
  border-radius: 4px;
  font-family: 'SFMono-Regular', Consolas, Menlo, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  color: #606266;
}

.metric-owner {
  font-size: 12px;
  color: #909399;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.metric-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.metric-header-title {
  font-size: 14px;
  font-weight: 600;
}

.alarm-alert {
  margin-bottom: 12px;
}

.metric-monitor {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  min-height: 30px;
}

.metric-value {
  font-size: 26px;
  font-weight: 700;
  color: #303133;
}

.metric-value.alarm {
  color: #f56c6c;
}

.metric-time {
  font-size: 12px;
  color: #909399;
}

.metric-no-val {
  font-size: 12px;
  color: #c0c4cc;
}

.metric-threshold {
  margin-left: 8px;
  color: #e6a23c;
}

/* ---------- 指标统计头与筛选工具栏 ---------- */
.metric-stats {
  display: flex;
  gap: 40px;
  padding: 14px 18px;
  margin-bottom: 12px;
  background: var(--el-bg-color-page, #f7f8fa);
  border-radius: 8px;
}

.mstat-value {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
}

.mstat-value.mstat-warn {
  color: var(--el-color-danger);
}

.mstat-label {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 2px;
}

.metric-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.metric-toolbar-tip {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-muted);
}
</style>
