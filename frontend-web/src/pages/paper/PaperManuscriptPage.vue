<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import DOMPurify from 'dompurify'
import { ElMessage } from 'element-plus'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import { paperApi } from '@/api/endpoints'
import { showApiError } from '@/api/http'
import { usePaperStore } from '@/stores/paper'
import type { PaperTraceabilityResult } from '@/types/api'
import { buildPaperManuscriptMarkdown } from '@/mappers/paperToManuscript'

const route = useRoute()
const router = useRouter()
const paper = usePaperStore()

const taskId = computed(() => String(route.params.taskId))
const loading = ref(false)
const traceability = ref<PaperTraceabilityResult | null>(null)
const evidencePanelVisible = ref(false)
const activeCitation = ref<number | null>(null)
const sourceFilter = ref<string>('')
const yearFilter = ref<number | ''>('')
const chapterFilter = ref<number | ''>('')
const contentRoot = ref<HTMLElement | null>(null)

const manuscriptMd = computed(() => {
  if (!paper.outline) return ''
  return buildPaperManuscriptMarkdown(paper.outline)
})

const manuscriptChapters = computed(() => {
  const raw = (paper.outline?.manuscript as any)?.chapters
  if (!Array.isArray(raw)) return []
  return raw.map((chapter: any, chapterIndex: number) => ({
    chapterIndex,
    title: String(chapter?.title || chapter?.name || `第${chapterIndex + 1}章`),
    sections: Array.isArray(chapter?.sections)
      ? chapter.sections.map((section: any, sectionIndex: number) => ({
          sectionIndex,
          title: String(section?.title || section?.name || `第${chapterIndex + 1}.${sectionIndex + 1}节`),
          content: String(section?.content || ''),
          citations: Array.isArray(section?.citations)
            ? section.citations
                .map((v: unknown) => Number(v))
                .filter((v: number) => Number.isFinite(v) && v > 0)
            : [],
        }))
      : [],
  }))
})

const chapterCitationMap = computed(() => {
  const map = new Map<number, number[]>()
  ;(traceability.value?.chapters || []).forEach((chapter) => {
    const uniq = Array.from(new Set((chapter.citationIndices || []).filter((n) => Number.isFinite(n))))
    map.set(chapter.chapterIndex, uniq)
  })
  return map
})

const chapterOptions = computed(() => {
  return manuscriptChapters.value.map((chapter) => ({
    label: chapter.title,
    value: chapter.chapterIndex,
  }))
})

const sourceOptions = computed(() => {
  const set = new Set<string>()
  ;(traceability.value?.globalEvidenceList || []).forEach((item) => {
    if (item.source) set.add(item.source)
  })
  return Array.from(set).sort()
})

const yearOptions = computed(() => {
  const set = new Set<number>()
  ;(traceability.value?.globalEvidenceList || []).forEach((item) => {
    if (typeof item.year === 'number') set.add(item.year)
  })
  return Array.from(set).sort((a, b) => b - a)
})

const filteredEvidence = computed(() => {
  let list = [...(traceability.value?.globalEvidenceList || [])]

  if (chapterFilter.value !== '' && chapterFilter.value !== null) {
    const indices = new Set(chapterCitationMap.value.get(chapterFilter.value) || [])
    list = list.filter((item) => indices.has(item.citationIndex))
  }
  if (sourceFilter.value) {
    list = list.filter((item) => item.source === sourceFilter.value)
  }
  if (yearFilter.value !== '' && yearFilter.value !== null) {
    list = list.filter((item) => item.year === yearFilter.value)
  }

  return list.sort((a, b) => a.citationIndex - b.citationIndex)
})

const load = async () => {
  if (!taskId.value) return
  loading.value = true
  try {
    const [outline, manuscript, trace] = await Promise.all([
      paperApi.getOutline(taskId.value),
      paperApi.getManuscript(taskId.value),
      paperApi.getTraceability(taskId.value).catch(() => null),
    ])
    paper.taskId = taskId.value
    paper.outline = {
      ...outline,
      topic: manuscript.topic || outline.topic,
      topicRefined: manuscript.topicRefined || outline.topicRefined,
      manuscript: manuscript.manuscript,
      qualityScore: manuscript.qualityScore ?? outline.qualityScore,
      rewriteRound: manuscript.rewriteRound ?? outline.rewriteRound,
    }
    traceability.value = trace
    if (route.query.panel === 'evidence') {
      evidencePanelVisible.value = true
    }
  } catch (e) {
    showApiError(e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})

const copyText = async (text: string) => {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.left = '-9999px'
    ta.style.top = '0'
    document.body.appendChild(ta)
    ta.focus()
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}

const onCopy = async () => {
  const ok = await copyText(manuscriptMd.value)
  if (ok) {
    ElMessage.success('已复制文稿（Markdown）')
  } else {
    ElMessage.error('复制失败，请手动选择复制')
  }
}

const escapeHtml = (text: string) => {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

const renderSectionContent = (content: string) => {
  const escaped = escapeHtml(content || '')
  const replaced = escaped.replace(/\[(\d+)]/g, (_, idx) => {
    const n = Number(idx)
    const activeClass = activeCitation.value === n ? ' citation-marker-active' : ''
    return `<button type="button" class="citation-marker${activeClass}" data-citation="${n}">[${n}]</button>`
  })
  const withBreaks = replaced.replace(/\n/g, '<br/>')
  return DOMPurify.sanitize(withBreaks, {
    ALLOWED_TAGS: ['br', 'button'],
    ALLOWED_ATTR: ['class', 'type', 'data-citation'],
  })
}

const focusCitation = async (citationIndex: number, scrollPanel = true, scrollContent = true) => {
  activeCitation.value = citationIndex
  evidencePanelVisible.value = true
  await nextTick()

  if (scrollPanel) {
    const card = document.getElementById(`evidence-card-${citationIndex}`)
    card?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  }
  if (scrollContent) {
    const marker = contentRoot.value?.querySelector(`[data-citation="${citationIndex}"]`) as HTMLElement | null
    marker?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }
}

const onContentClick = (event: MouseEvent) => {
  const target = event.target as HTMLElement | null
  if (!target) return
  const marker = target.closest('[data-citation]') as HTMLElement | null
  if (!marker) return
  const citationIndex = Number(marker.dataset.citation)
  if (!Number.isFinite(citationIndex)) return
  void focusCitation(citationIndex, true, false)
}

const chapterCitations = (chapterIndex: number, fallback: number[]) => {
  const fromTrace = chapterCitationMap.value.get(chapterIndex)
  if (fromTrace && fromTrace.length) return fromTrace
  return fallback
}
</script>

<template>
  <div class="flex flex-col gap-4">
    <div class="rounded-2xl border bg-white p-5">
      <div class="flex items-start justify-between gap-4">
        <div>
          <div class="text-base font-semibold">论文文稿预览</div>
          <div class="mt-1 text-sm text-slate-500">任务 ID：{{ taskId }}</div>
        </div>
        <div class="flex flex-wrap items-center gap-2">
          <el-button :loading="loading" @click="load">刷新</el-button>
          <el-button @click="router.push({ name: 'paper-outline-result', params: { taskId } })">查看大纲</el-button>
          <el-button type="primary" plain :disabled="!traceability" @click="evidencePanelVisible = !evidencePanelVisible">
            {{ evidencePanelVisible ? '隐藏溯源证据' : '查看溯源证据' }}
          </el-button>
          <el-button type="primary" :disabled="!manuscriptMd" @click="onCopy">复制文稿</el-button>
        </div>
      </div>
    </div>

    <div class="grid grid-cols-1 gap-4" :class="evidencePanelVisible ? 'xl:grid-cols-[minmax(0,1fr)_360px]' : 'xl:grid-cols-1'">
      <div class="rounded-2xl border bg-white p-6">
        <div v-if="loading" class="space-y-3">
          <div class="h-4 w-1/3 animate-pulse rounded bg-slate-100" />
          <div class="h-3 w-2/3 animate-pulse rounded bg-slate-100" />
          <div class="h-3 w-full animate-pulse rounded bg-slate-100" />
          <div class="h-3 w-5/6 animate-pulse rounded bg-slate-100" />
        </div>

        <div v-else-if="!manuscriptChapters.length">
          <div class="mb-3 text-sm text-slate-500">暂无结构化文稿，已回退为 Markdown 展示。</div>
          <MarkdownRenderer v-if="manuscriptMd" :content="manuscriptMd" />
          <div v-else class="text-sm text-slate-500">暂无文稿内容，请先生成论文框架结果。</div>
        </div>

        <div v-else ref="contentRoot" class="space-y-6" @click="onContentClick">
          <article v-for="chapter in manuscriptChapters" :key="chapter.chapterIndex" class="rounded-xl border border-slate-200 bg-slate-50/50 p-4">
            <header class="mb-3">
              <h2 class="text-lg font-semibold text-slate-800">{{ chapter.title }}</h2>
              <div class="mt-2 flex flex-wrap gap-2" v-if="chapterCitations(chapter.chapterIndex, []).length">
                <button
                  v-for="idx in chapterCitations(chapter.chapterIndex, [])"
                  :key="idx"
                  type="button"
                  class="rounded-full border border-slate-300 bg-white px-2 py-0.5 text-xs text-slate-700 transition hover:border-blue-400"
                  @click="focusCitation(idx)"
                >
                  [{{ idx }}]
                </button>
              </div>
            </header>

            <section v-for="section in chapter.sections" :key="`${chapter.chapterIndex}-${section.sectionIndex}`" class="mb-4 rounded-lg bg-white p-3 shadow-sm">
              <h3 class="text-sm font-semibold text-slate-700">{{ section.title }}</h3>
              <p
                class="manuscript-content mt-2 text-sm leading-7 text-slate-700"
                v-html="renderSectionContent(section.content)"
              />
              <div v-if="!section.content" class="mt-2 text-xs text-slate-400">（该节暂无扩写内容）</div>
            </section>
          </article>
        </div>
      </div>

      <aside v-if="evidencePanelVisible" class="rounded-2xl border bg-white p-4">
        <div class="flex items-center justify-between">
          <div class="text-sm font-semibold">证据来源（{{ filteredEvidence.length }}）</div>
          <el-button text size="small" @click="evidencePanelVisible = false">收起</el-button>
        </div>

        <div class="mt-3 grid grid-cols-1 gap-2">
          <el-select v-model="chapterFilter" clearable placeholder="按章节筛选" size="small">
            <el-option v-for="opt in chapterOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
          <el-select v-model="sourceFilter" clearable placeholder="按来源筛选" size="small">
            <el-option v-for="source in sourceOptions" :key="source" :label="source" :value="source" />
          </el-select>
          <el-select v-model="yearFilter" clearable placeholder="按年份筛选" size="small">
            <el-option v-for="year in yearOptions" :key="year" :label="String(year)" :value="year" />
          </el-select>
        </div>

        <div class="mt-4 max-h-[68vh] space-y-3 overflow-auto pr-1">
          <div v-if="!filteredEvidence.length" class="rounded-lg border border-dashed p-3 text-xs text-slate-500">
            当前筛选条件下暂无证据。
          </div>
          <article
            v-for="item in filteredEvidence"
            :id="`evidence-card-${item.citationIndex}`"
            :key="item.citationIndex"
            class="cursor-pointer rounded-lg border p-3 transition"
            :class="activeCitation === item.citationIndex ? 'border-blue-500 bg-blue-50' : 'border-slate-200 hover:border-blue-300'"
            @click="focusCitation(item.citationIndex, false, true)"
          >
            <div class="mb-1 text-xs font-semibold text-blue-700">[{{ item.citationIndex }}] {{ item.title || '未命名证据' }}</div>
            <div class="line-clamp-4 text-xs leading-5 text-slate-600">{{ item.content }}</div>
            <div class="mt-2 flex flex-wrap items-center gap-1 text-[11px] text-slate-500">
              <span v-if="item.source" class="rounded bg-slate-100 px-1.5 py-0.5">{{ item.source }}</span>
              <span v-if="item.year" class="rounded bg-slate-100 px-1.5 py-0.5">{{ item.year }}</span>
              <span v-if="typeof item.rerankScore === 'number'" class="rounded bg-slate-100 px-1.5 py-0.5">score {{ item.rerankScore.toFixed(2) }}</span>
            </div>
            <a
              v-if="item.url"
              class="mt-2 inline-block text-xs text-blue-600 hover:text-blue-800"
              :href="item.url"
              target="_blank"
              rel="noopener noreferrer"
              @click.stop
            >
              查看原文
            </a>
          </article>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
:deep(.citation-marker) {
  border: none;
  background: transparent;
  color: #2563eb;
  font-weight: 600;
  cursor: pointer;
  padding: 0 2px;
}

:deep(.citation-marker:hover) {
  color: #1d4ed8;
  text-decoration: underline;
}

:deep(.citation-marker-active) {
  background-color: #dbeafe;
  border-radius: 4px;
}

.manuscript-content {
  white-space: normal;
}
</style>

