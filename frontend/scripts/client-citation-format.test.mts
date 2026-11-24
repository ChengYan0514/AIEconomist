import assert from "node:assert/strict"
import { test } from "node:test"
import {
  buildAnnotatedHighlightSegments,
  exportCitationsLocally,
  formatCitationsLocally,
} from "../src/client/citation-format.ts"

const statements = [
  {
    statementId: "s1",
    text: "Minimum wage increases compress lower-tail wages.",
    startChar: 0,
    endChar: 49,
    needsCitation: true,
    query: "minimum wage lower-tail wages",
    confidence: 0.92,
  },
  {
    statementId: "s2",
    text: "紧缩性货币政策显著抑制受融资约束企业的投资。",
    startChar: 50,
    endChar: 72,
    needsCitation: true,
    query: "monetary tightening constrained firms investment",
    confidence: 0.9,
  },
]

const originalText = `${statements[0].text} ${statements[1].text}`

const referencesByStatement = {
  s1: [
    {
      referenceId: "W1",
      workId: "W1",
      paperId: "p1",
      doi: "10.1093/qje/qjz014",
      title: "The Effect of Minimum Wages on Low-Wage Jobs",
      authors: ["Doruk Cengiz", "Arindrajit Dube", "Attila Lindner"],
      year: 2019,
      venue: "Quarterly Journal of Economics",
      sourceId: "S203860005",
      url: null,
      metadataSource: "LOCAL_DB",
      rankScore: 0.94,
      maxSupportScore: 0.95,
      maxConfidence: 0.92,
      chunks: [],
    },
    {
      referenceId: "W2",
      workId: "W2",
      paperId: null,
      doi: "10.1257/aer.84.4.772",
      title: "Minimum Wages and Employment",
      authors: ["David Card", "Alan B. Krueger"],
      year: 1994,
      venue: "American Economic Review",
      sourceId: "S23254222",
      url: null,
      metadataSource: "ENGINE",
      rankScore: 0.88,
      maxSupportScore: 0.89,
      maxConfidence: 0.9,
      chunks: [],
    },
  ],
  s2: [
    {
      referenceId: "W3",
      workId: "W3",
      paperId: "p3",
      doi: "10.3982/ECTA15949",
      title: "Financial Heterogeneity and {Monetary} Policy",
      authors: ["Pablo Ottonello", "Thomas Winberry"],
      year: 2020,
      venue: "Econometrica",
      sourceId: "S95464858",
      url: null,
      metadataSource: "LOCAL_DB",
      rankScore: 0.93,
      maxSupportScore: 0.94,
      maxConfidence: 0.91,
      chunks: [],
    },
  ],
}

test("formats APA, IEEE, Chicago, and MLA inline citations before English and Chinese punctuation", () => {
  const selected = { s1: ["W1", "W2"], s2: ["W3"] }

  const apa = formatCitationsLocally(originalText, statements, referencesByStatement, selected, "apa")
  assert.match(apa.annotatedText, /lower-tail wages \(Cengiz et al\., 2019; Card & Krueger, 1994\)\./)
  assert.match(apa.annotatedText, /受融资约束企业的投资 \(Ottonello & Winberry, 2020\)。/)
  assert.match(apa.bibliography, /Card, D\., & Krueger, A\. B\. \(1994\)/)

  const ieee = formatCitationsLocally(originalText, statements, referencesByStatement, selected, "ieee")
  assert.match(ieee.annotatedText, /lower-tail wages \[1, 2\]\./)
  assert.match(ieee.annotatedText, /受融资约束企业的投资 \[3\]。/)
  assert.match(ieee.bibliography, /^\[1\] Doruk Cengiz/)

  const chicago = formatCitationsLocally(originalText, statements, referencesByStatement, selected, "chicago-author-date")
  assert.match(chicago.annotatedText, /\(Cengiz et al\. 2019; Card and Krueger 1994\)\./)

  const mla = formatCitationsLocally(originalText, statements, referencesByStatement, selected, "mla")
  assert.match(mla.annotatedText, /\(Cengiz et al\.; Card and Krueger\)\./)
})

test("omits inline citation when user unchecks all references for a statement", () => {
  const selected = { s1: [], s2: ["W3"] }
  const apa = formatCitationsLocally(originalText, statements, referencesByStatement, selected, "apa")
  assert.ok(apa.annotatedText.startsWith("Minimum wage increases compress lower-tail wages."))
  assert.match(apa.annotatedText, /\(Ottonello & Winberry, 2020\)。/)
  assert.equal(apa.bibliography.split("\n").length, 1)
})

test("exports deduplicated BibTeX with escaped braces and maps annotated spans back to statementIds", () => {
  const selected = { s1: ["W1", "W2"], s2: ["W3"] }
  const bib = exportCitationsLocally("测试文稿", originalText, statements, referencesByStatement, selected, "apa", "bibtex")
  assert.ok(bib.filename.endsWith(".bib"))
  assert.match(bib.content, /@article\{cengiz2019,/)
  assert.match(bib.content, /title = \{Financial Heterogeneity and \\\{Monetary\\\} Policy\}/)

  const apa = formatCitationsLocally(originalText, statements, referencesByStatement, selected, "apa")
  const segments = buildAnnotatedHighlightSegments(apa.annotatedText, statements)
  const highlightedIds = segments.filter((s) => s.statementId !== null).map((s) => s.statementId)
  assert.deepEqual(highlightedIds, ["s1", "s2"])
})
