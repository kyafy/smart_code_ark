import type { PaperOutlineResult } from '@/types/api'

type AnyObj = Record<string, any>

const asArray = (v: unknown): any[] => {
  if (Array.isArray(v)) return v
  return []
}

const text = (v: any): string => {
  if (v == null) return ''
  return String(v).trim()
}

const headingSafe = (v: string) => v.replace(/\n+/g, ' ').trim()

const bulletLink = (title: string, url: string) => {
  const t = headingSafe(title || '未命名文献')
  const u = headingSafe(url)
  if (u) return `- ${t}（${u}）`
  return `- ${t}`
}

const renderEvidence = (items: any[]) => {
  const ev = asArray(items)
  if (ev.length === 0) return ''
  const lines = ev
    .slice(0, 5)
    .map((e) => {
      const o = (e ?? {}) as AnyObj
      const title = text(o.title) || text(o.paperTitle)
      const url = text(o.url)
      return bulletLink(title, url)
    })
  return ['\n证据线索：', ...lines].join('\n')
}

const sectionBody = (section: AnyObj) => {
  const content = text(section.content)
  if (content) return content
  const parts = [
    text(section.coreArgument),
    text(section.method),
    text(section.dataPlan),
    text(section.expectedResult),
  ].filter(Boolean)
  return parts.join('\n\n')
}

export const buildPaperManuscriptMarkdown = (outline: PaperOutlineResult): string => {
  const title = headingSafe(outline.topicRefined || outline.topic || '论文文稿')
  const citationStyle = headingSafe(outline.citationStyle || 'GB/T 7714')
  const questions = Array.isArray(outline.researchQuestions) ? outline.researchQuestions.filter(Boolean).map(String) : []
  const manuscript = (outline.manuscript ?? {}) as AnyObj
  const chapters = asArray(manuscript.chapters ?? outline.chapters)
  const refs = asArray(outline.references)

  const lines: string[] = []

  lines.push(`# ${title}`)
  lines.push('')
  lines.push(`引用规范：${citationStyle}`)
  lines.push('')
  lines.push('## 摘要')
  lines.push('（此处根据研究背景、方法与结论撰写摘要。）')
  lines.push('')
  lines.push('## 关键词')
  lines.push('（此处填写 3~5 个关键词。）')
  lines.push('')

  if (questions.length > 0) {
    lines.push('## 研究问题')
    questions.forEach((q, idx) => {
      lines.push(`${idx + 1}. ${headingSafe(q)}`)
    })
    lines.push('')
  }

  lines.push('## 目录')
  chapters.forEach((c, idx) => {
    const o = (c ?? {}) as AnyObj
    const name = headingSafe(text(o.title) || text(o.name) || `第${idx + 1}章`)
    lines.push(`- ${name}`)
    const sections = asArray(o.sections)
    sections.forEach((s) => {
      const so = (s ?? {}) as AnyObj
      const sn = headingSafe(text(so.title) || text(so.name) || '')
      if (sn) lines.push(`  - ${sn}`)
      const subs = asArray(so.subsections)
      subs.forEach((sub) => {
        const subo = (sub ?? {}) as AnyObj
        const subn = headingSafe(text(subo.title) || text(subo.name) || '')
        if (subn) lines.push(`    - ${subn}`)
      })
    })
  })
  lines.push('')

  chapters.forEach((c, idx) => {
    const o = (c ?? {}) as AnyObj
    const chapterTitle = headingSafe(text(o.title) || text(o.name) || `第${idx + 1}章`)
    lines.push(`## ${chapterTitle}`)
    if (text(o.objective)) {
      lines.push('')
      lines.push(text(o.objective))
    }
    const sections = asArray(o.sections)
    if (sections.length === 0) {
      lines.push('')
      lines.push('（此章内容待补充。）')
      lines.push('')
      return
    }
    sections.forEach((s) => {
      const so = (s ?? {}) as AnyObj
      const sectionTitle = headingSafe(text(so.title) || text(so.name) || '未命名小节')
      lines.push('')
      lines.push(`### ${sectionTitle}`)
      const body = sectionBody(so)
      if (body) {
        lines.push('')
        lines.push(body)
      } else {
        lines.push('')
        lines.push('（此处根据证据线索撰写段落内容。）')
      }

      const citations = asArray(so.citations)
      if (citations.length > 0) {
        const validCitations = citations
          .map((c) => headingSafe(text(c)))
          .filter((c) => !!c && !c.startsWith('NO_RESULT_'))
          .slice(0, 5)
        if (validCitations.length > 0) {
        lines.push('')
        lines.push('参考依据：')
          validCitations.forEach((line) => {
            lines.push(`- ${line}`)
          })
        }
      }

      const ev = renderEvidence(asArray(so.evidence) || asArray(so.evidences))
      if (ev) {
        lines.push(ev)
      }

      const subs = asArray(so.subsections)
      subs.forEach((sub) => {
        const subo = (sub ?? {}) as AnyObj
        const subTitle = headingSafe(text(subo.title) || text(subo.name) || '未命名子节')
        lines.push('')
        lines.push(`#### ${subTitle}`)
        lines.push('')
        lines.push(text(subo.summary) || '（此处根据证据线索扩写子节内容。）')

        const subEv = renderEvidence(asArray(subo.evidence) || asArray(subo.evidences))
        if (subEv) {
          lines.push(subEv)
        }
      })
    })
    lines.push('')
  })

  lines.push('## 参考文献')
  if (refs.length === 0) {
    lines.push('（暂无参考文献。）')
  } else {
    refs.forEach((r) => {
      const ro = (r ?? {}) as AnyObj
      const t = text(ro.title) || text(ro.name)
      const url = text(ro.url)
      lines.push(bulletLink(t, url))
    })
  }

  return lines.join('\n')
}
