import type { CitationReference, CitationStatement, CitationStyle, ExportFormat } from "./types"

function surname(author: string): string {
  const trimmed = author.trim()
  if (!trimmed) return "Unknown"
  if (trimmed.includes(",")) return trimmed.split(",")[0].trim()
  const parts = trimmed.split(/\s+/)
  return parts[parts.length - 1] || trimmed
}

function authorShort(authors: string[], conjunction: "&" | "and"): string {
  if (!authors.length) return "Unknown"
  if (authors.length === 1) return surname(authors[0])
  if (authors.length === 2) return `${surname(authors[0])} ${conjunction} ${surname(authors[1])}`
  return `${surname(authors[0])} et al.`
}

function formatAuthorListApa(authors: string[]): string {
  if (!authors.length) return "Unknown."
  const formatted = authors.map((a) => {
    const parts = a.trim().split(/\s+/)
    if (parts.length <= 1) return a.trim()
    const last = parts[parts.length - 1]
    const initials = parts.slice(0, -1).map((p) => p[0]?.toUpperCase() + ".").join(" ")
    return `${last}, ${initials}`
  })
  if (formatted.length === 1) return formatted[0]
  if (formatted.length === 2) return `${formatted[0]}, & ${formatted[1]}`
  return `${formatted.slice(0, -1).join(", ")}, & ${formatted[formatted.length - 1]}`
}

function formatAuthorListFull(authors: string[]): string {
  if (!authors.length) return "Unknown"
  if (authors.length === 1) return authors[0]
  if (authors.length === 2) return `${authors[0]} and ${authors[1]}`
  return `${authors.slice(0, -1).join(", ")}, and ${authors[authors.length - 1]}`
}

function normalizeDoiUrl(doi: string | null): string {
  if (!doi) return ""
  const clean = doi.trim().replace(/^https?:\/\/(dx\.)?doi\.org\//i, "").replace(/^doi:\s*/i, "")
  return clean ? `https://doi.org/${clean}` : ""
}

function formatBibEntry(ref: CitationReference, style: CitationStyle, ieeeIndex?: number): string {
  const year = ref.year ?? "n.d."
  const venue = ref.venue ? `${ref.venue}.` : ""
  const doiUrl = normalizeDoiUrl(ref.doi)
  const suffix = doiUrl || ref.url || ""
  if (style === "ieee") {
    const authors = formatAuthorListFull(ref.authors)
    const doiPart = ref.doi ? ` doi: ${ref.doi}.` : ""
    return `[${ieeeIndex ?? 1}] ${authors}, "${ref.title}," ${ref.venue || "Working Paper"}, ${year}.${doiPart}`.trim()
  }
  if (style === "chicago-author-date") {
    return `${formatAuthorListFull(ref.authors)}. ${year}. "${ref.title}." ${venue} ${suffix}`.replace(/\s+/g, " ").trim()
  }
  if (style === "mla") {
    return `${formatAuthorListFull(ref.authors)}. "${ref.title}." ${ref.venue || "Working Paper"}, ${year}. ${suffix}`.replace(/\s+/g, " ").trim()
  }
  return `${formatAuthorListApa(ref.authors)} (${year}). ${ref.title}. ${venue} ${suffix}`.replace(/\s+/g, " ").trim()
}

function formatInlineGroup(refs: CitationReference[], style: CitationStyle, ieeeOrder: Map<string, number>): string {
  if (!refs.length) return ""
  if (style === "ieee") {
    const nums = refs.map((r) => ieeeOrder.get(r.referenceId) ?? 0).filter((n) => n > 0).sort((a, b) => a - b)
    return `[${nums.join(", ")}]`
  }
  if (style === "chicago-author-date") {
    const items = refs.map((r) => `${authorShort(r.authors, "and")} ${r.year ?? "n.d."}`)
    return `(${items.join("; ")})`
  }
  if (style === "mla") {
    const items = refs.map((r) => authorShort(r.authors, "and"))
    return `(${items.join("; ")})`
  }
  const items = refs.map((r) => `${authorShort(r.authors, "&")}, ${r.year ?? "n.d."}`)
  return `(${items.join("; ")})`
}

function insertionOffset(text: string, statement: CitationStatement): number {
  let end = statement.endChar
  if (end <= 0 || end > text.length || text.slice(statement.startChar, statement.endChar) !== statement.text) {
    const found = text.indexOf(statement.text)
    if (found < 0) return -1
    end = found + statement.text.length
  }
  let pos = end
  while (pos > 0 && /\s/.test(text[pos - 1])) pos--
  if (pos > 0 && /[.!?。！？;；]/.test(text[pos - 1])) pos--
  return pos
}

export function formatCitationsLocally(
  originalText: string,
  statements: CitationStatement[],
  referencesByStatement: Record<string, CitationReference[]>,
  selectedReferenceIds: Record<string, string[]>,
  style: CitationStyle,
): { annotatedText: string; bibliography: string } {
  const orderedUnique: CitationReference[] = []
  const ieeeOrder = new Map<string, number>()

  const activeStatements = statements
    .filter((s) => s.needsCitation)
    .map((statement) => {
      const candidates = referencesByStatement[statement.statementId] ?? []
      const byId = new Map(candidates.map((r) => [r.referenceId, r]))
      const chosen = (selectedReferenceIds[statement.statementId] ?? [])
        .map((id) => byId.get(id))
        .filter((r): r is CitationReference => Boolean(r))
      for (const ref of chosen) {
        if (!ieeeOrder.has(ref.referenceId)) {
          ieeeOrder.set(ref.referenceId, orderedUnique.length + 1)
          orderedUnique.push(ref)
        }
      }
      return { statement, chosen }
    })

  const edits = activeStatements
    .filter(({ chosen }) => chosen.length > 0)
    .map(({ statement, chosen }) => ({
      offset: insertionOffset(originalText, statement),
      inline: formatInlineGroup(chosen, style, ieeeOrder),
    }))
    .filter((e) => e.offset >= 0 && e.inline.length > 0)
    .sort((a, b) => b.offset - a.offset)

  let annotatedText = originalText
  for (const edit of edits) {
    const prevChar = edit.offset > 0 ? annotatedText[edit.offset - 1] : ""
    const spacer = prevChar && !/[\s(（]/.test(prevChar) ? " " : ""
    annotatedText = annotatedText.slice(0, edit.offset) + spacer + edit.inline + annotatedText.slice(edit.offset)
  }

  const bibRefs = style === "ieee"
    ? orderedUnique
    : [...orderedUnique].sort((a, b) => {
      const aKey = `${surname(a.authors[0] ?? "")} ${a.year ?? 9999} ${a.title}`.toLowerCase()
      const bKey = `${surname(b.authors[0] ?? "")} ${b.year ?? 9999} ${b.title}`.toLowerCase()
      return aKey.localeCompare(bKey)
    })

  const bibliography = bibRefs
    .map((ref) => formatBibEntry(ref, style, ieeeOrder.get(ref.referenceId)))
    .join("\n")

  return { annotatedText, bibliography }
}

function escapeBibtex(value: string): string {
  return value.replace(/\\/g, "\\\\").replace(/[{}]/g, (m) => `\\${m}`)
}

export function exportCitationsLocally(
  title: string,
  originalText: string,
  statements: CitationStatement[],
  referencesByStatement: Record<string, CitationReference[]>,
  selectedReferenceIds: Record<string, string[]>,
  style: CitationStyle,
  format: ExportFormat,
): { filename: string; mime: string; content: string } {
  const { annotatedText, bibliography } = formatCitationsLocally(
    originalText, statements, referencesByStatement, selectedReferenceIds, style,
  )
  const slug = title.replace(/[^a-zA-Z0-9\u4e00-\u9fa5_-]+/g, "-").slice(0, 32) || "citations"

  if (format === "txt") {
    return {
      filename: `${slug}.txt`,
      mime: "text/plain;charset=utf-8",
      content: `${annotatedText}\n\nReferences\n${bibliography || "(无已采纳参考文献)"}`,
    }
  }
  if (format === "md") {
    const bibLines = bibliography
      ? bibliography.split("\n").map((line) => `- ${line}`).join("\n")
      : "_无已采纳参考文献_"
    return {
      filename: `${slug}.md`,
      mime: "text/markdown;charset=utf-8",
      content: `# ${title}\n\n## 正文\n\n${annotatedText}\n\n## 参考文献\n\n${bibLines}\n`,
    }
  }

  const seen = new Map<string, CitationReference>()
  for (const statement of statements) {
    const byId = new Map((referencesByStatement[statement.statementId] ?? []).map((r) => [r.referenceId, r]))
    for (const id of selectedReferenceIds[statement.statementId] ?? []) {
      const ref = byId.get(id)
      if (ref && !seen.has(ref.referenceId)) seen.set(ref.referenceId, ref)
    }
  }
  const usedKeys = new Set<string>()
  const entries = [...seen.values()].map((ref, idx) => {
    const base = `${surname(ref.authors[0] ?? "ref").toLowerCase().replace(/[^a-z0-9]/g, "") || "ref"}${ref.year ?? "nd"}`
    let key = base
    let suffix = 1
    while (usedKeys.has(key)) key = `${base}_${suffix++}`
    usedKeys.add(key)
    const fields = [
      `  author = {${escapeBibtex(ref.authors.join(" and ") || "Unknown")}}`,
      `  title = {${escapeBibtex(ref.title)}}`,
      ref.venue ? `  journal = {${escapeBibtex(ref.venue)}}` : null,
      ref.year ? `  year = {${ref.year}}` : null,
      ref.doi ? `  doi = {${escapeBibtex(ref.doi)}}` : null,
      ref.url ? `  url = {${escapeBibtex(ref.url)}}` : null,
    ].filter(Boolean)
    return `@article{${key || `ref_${idx + 1}`},\n${fields.join(",\n")}\n}`
  })
  return {
    filename: `${slug}.bib`,
    mime: "application/x-bibtex;charset=utf-8",
    content: entries.join("\n\n") + (entries.length ? "\n" : ""),
  }
}

export interface HighlightSegment {
  text: string
  statementId: string | null
}

function stripTrailingPunctuation(text: string): string {
  return text.trim().replace(/[.!?。！？;；]+$/, "")
}

export function buildAnnotatedHighlightSegments(
  annotatedText: string,
  statements: CitationStatement[],
): HighlightSegment[] {
  const spans: { start: number; end: number; statementId: string }[] = []
  let cursor = 0
  for (const statement of statements) {
    const core = stripTrailingPunctuation(statement.text)
    if (!core) continue
    let idx = annotatedText.indexOf(core, cursor)
    if (idx < 0) idx = annotatedText.indexOf(core)
    if (idx < 0) continue
    let end = idx + core.length
    const tail = annotatedText.slice(end)
    const inlineMatch = tail.match(/^(\s*(?:\([^()\n]{2,160}\)|\[[\d,\s–-]{1,40}\]))?([.!?。！？;；]?)/)
    if (inlineMatch) end += inlineMatch[0].length
    spans.push({ start: idx, end, statementId: statement.statementId })
    cursor = end
  }
  spans.sort((a, b) => a.start - b.start)
  const segments: HighlightSegment[] = []
  let pos = 0
  for (const span of spans) {
    if (span.start < pos) continue
    if (span.start > pos) segments.push({ text: annotatedText.slice(pos, span.start), statementId: null })
    segments.push({ text: annotatedText.slice(span.start, span.end), statementId: span.statementId })
    pos = span.end
  }
  if (pos < annotatedText.length) segments.push({ text: annotatedText.slice(pos), statementId: null })
  return segments.length ? segments : [{ text: annotatedText, statementId: null }]
}
