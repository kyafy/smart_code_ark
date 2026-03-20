import type { PaperOutlineResult } from '@/types/api'

export type CodegenSeedSpec = {
  title: string
  description: string
  projectType: 'web'
}

export const mapPaperOutlineToCodegenSeed = (outline: PaperOutlineResult): CodegenSeedSpec => {
  const questions = Array.isArray(outline.researchQuestions) ? outline.researchQuestions.filter(Boolean) : []
  const chapterNames = Array.isArray(outline.chapters)
    ? outline.chapters
        .map((c: any) => String(c?.title || c?.name || '').trim())
        .filter(Boolean)
    : []
  const core = [
    `论文题目：${outline.topicRefined || outline.topic}`,
    questions.length ? `研究问题：${questions.join('；')}` : '',
    chapterNames.length ? `章节结构：${chapterNames.join('、')}` : '',
    `引用规范：${outline.citationStyle || 'GB/T 7714'}`
  ].filter(Boolean)

  return {
    title: (outline.topicRefined || outline.topic || '论文驱动系统').slice(0, 40),
    description: core.join('\n'),
    projectType: 'web'
  }
}
