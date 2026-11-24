import {
  Activity, Bot, CheckCircle2, Cpu, Eraser, PlugZap, RefreshCw, Save, Server, Sliders, Sparkles, XCircle,
} from "lucide-react"
import { useEffect, useState, type FormEvent } from "react"
import { api, dateLabel, errorMessage, useQuery } from "./api"
import { Loading, Notice } from "./components/shared"
import { Button } from "./components/ui/button"
import { SectionHeader } from "./components/workspace"

type ThinkingStyle =
  | "AUTO"
  | "THINKING_OBJECT"
  | "ENABLE_THINKING"
  | "CHAT_TEMPLATE_KWARGS"
  | "OLLAMA_THINK"
  | "REASONING_EFFORT"
  | "NONE"

interface ServiceSettingsView {
  mineru: { url: string; tier: string; timeoutSeconds: number }
  causal: { enabled: boolean; url: string; defaultMode: "RAW" | "STANDARDIZED"; maxCalls: number }
  chat: {
    baseUrl: string
    model: string
    apiKeyConfigured: boolean
    apiKeyMask: string
    enableThinking: boolean
    thinkingStyle: ThinkingStyle
    effectiveThinkingStyle: ThinkingStyle
  }
  embedding: {
    baseUrl: string
    model: string
    apiKeyConfigured: boolean
    apiKeyMask: string
  }
  updatedAt: string | null
  updatedBy: string | null
}

interface ProbeResult {
  ok: boolean
  latencyMs: number
  message: string
  detail: string
}

const THINKING_STYLES: { value: ThinkingStyle; label: string }[] = [
  { value: "AUTO", label: "自动按厂商识别（推荐）" },
  { value: "THINKING_OBJECT", label: "DeepSeek / 火山方舟 / 智谱（thinking.type）" },
  { value: "ENABLE_THINKING", label: "阿里云百炼 / 硅基流动（enable_thinking）" },
  { value: "CHAT_TEMPLATE_KWARGS", label: "本地 vLLM / SGLang（chat_template_kwargs.enable_thinking）" },
  { value: "OLLAMA_THINK", label: "本地 Ollama（think）" },
  { value: "REASONING_EFFORT", label: "OpenAI / Gemini 推理模型（reasoning_effort）" },
  { value: "NONE", label: "不发送思考参数（普通非推理模型）" },
]

const CHAT_PRESETS = [
  { label: "DeepSeek 官网", baseUrl: "https://api.deepseek.com/v1", model: "deepseek-chat", style: "THINKING_OBJECT" as ThinkingStyle, keyless: false },
  { label: "硅基流动", baseUrl: "https://api.siliconflow.cn/v1", model: "deepseek-ai/DeepSeek-V3", style: "ENABLE_THINKING" as ThinkingStyle, keyless: false },
  { label: "阿里云百炼", baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1", model: "qwen3-max", style: "ENABLE_THINKING" as ThinkingStyle, keyless: false },
  { label: "本地 vLLM", baseUrl: "http://host.docker.internal:8000/v1", model: "Qwen/Qwen3-14B", style: "CHAT_TEMPLATE_KWARGS" as ThinkingStyle, keyless: true },
  { label: "本地 Ollama", baseUrl: "http://host.docker.internal:11434/v1", model: "qwen3:14b", style: "OLLAMA_THINK" as ThinkingStyle, keyless: true },
]

const EMBEDDING_PRESETS = [
  { label: "硅基流动", baseUrl: "https://api.siliconflow.cn/v1", model: "BAAI/bge-m3", keyless: false },
  { label: "阿里云百炼", baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1", model: "text-embedding-v4", keyless: false },
  { label: "本地 vLLM / Xinference", baseUrl: "http://host.docker.internal:8001/v1", model: "BAAI/bge-m3", keyless: true },
  { label: "本地 Ollama", baseUrl: "http://host.docker.internal:11434/v1", model: "bge-m3", keyless: true },
]

export function ServiceSettingsPage() {
  const query = useQuery<ServiceSettingsView>("/service-settings")
  const [mineruUrl, setMineruUrl] = useState("")
  const [mineruTier, setMineruTier] = useState("")
  const [mineruTimeout, setMineruTimeout] = useState("3600")

  const [causalEnabled, setCausalEnabled] = useState(false)
  const [causalUrl, setCausalUrl] = useState("")
  const [causalDefaultMode, setCausalDefaultMode] = useState<"RAW" | "STANDARDIZED">("RAW")
  const [causalMaxCalls, setCausalMaxCalls] = useState("2000")

  const [chatBaseUrl, setChatBaseUrl] = useState("")
  const [chatModel, setChatModel] = useState("")
  const [chatApiKey, setChatApiKey] = useState("")
  const [chatClearKey, setChatClearKey] = useState(false)
  const [chatEnableThinking, setChatEnableThinking] = useState(false)
  const [chatThinkingStyle, setChatThinkingStyle] = useState<ThinkingStyle>("AUTO")

  const [embBaseUrl, setEmbBaseUrl] = useState("")
  const [embModel, setEmbModel] = useState("")
  const [embApiKey, setEmbApiKey] = useState("")
  const [embClearKey, setEmbClearKey] = useState(false)

  const [saving, setSaving] = useState(false)
  const [saveNotice, setSaveNotice] = useState("")
  const [error, setError] = useState("")
  const [probing, setProbing] = useState<string | null>(null)
  const [probes, setProbes] = useState<Record<string, ProbeResult | undefined>>({})

  useEffect(() => {
    if (!query.data) return
    const d = query.data
    setMineruUrl(d.mineru.url)
    setMineruTier(d.mineru.tier)
    setMineruTimeout(String(d.mineru.timeoutSeconds))
    setCausalEnabled(d.causal.enabled)
    setCausalUrl(d.causal.url)
    setCausalDefaultMode(d.causal.defaultMode)
    setCausalMaxCalls(String(d.causal.maxCalls))
    setChatBaseUrl(d.chat.baseUrl)
    setChatModel(d.chat.model)
    setChatApiKey("")
    setChatClearKey(false)
    setChatEnableThinking(d.chat.enableThinking)
    setChatThinkingStyle(d.chat.thinkingStyle)
    setEmbBaseUrl(d.embedding.baseUrl)
    setEmbModel(d.embedding.model)
    setEmbApiKey("")
    setEmbClearKey(false)
  }, [query.data])

  async function handleSave(event: FormEvent) {
    event.preventDefault()
    setSaving(true)
    setError("")
    setSaveNotice("")
    try {
      const updated = await api<ServiceSettingsView>("/service-settings", {
        method: "PUT",
        body: JSON.stringify({
          mineru: { url: mineruUrl.trim(), tier: mineruTier, timeoutSeconds: Number(mineruTimeout) || 3600 },
          causal: {
            enabled: causalEnabled,
            url: causalUrl.trim(),
            defaultMode: causalDefaultMode,
            maxCalls: Number(causalMaxCalls) || 2000,
          },
          chat: {
            baseUrl: chatBaseUrl.trim(),
            model: chatModel.trim(),
            apiKey: chatApiKey,
            clearApiKey: chatClearKey,
            enableThinking: chatEnableThinking,
            thinkingStyle: chatThinkingStyle,
          },
          embedding: {
            baseUrl: embBaseUrl.trim(),
            model: embModel.trim(),
            apiKey: embApiKey,
            clearApiKey: embClearKey,
          },
        }),
      })
      setSaveNotice(`配置已保存并立即生效${updated.updatedAt ? `（${dateLabel(updated.updatedAt)}）` : ""}。`)
      query.reload()
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setSaving(false)
    }
  }

  async function runProbe(kind: "mineru" | "causal-engine" | "chat" | "embedding") {
    setProbing(kind)
    setError("")
    try {
      const body =
        kind === "mineru" ? { url: mineruUrl.trim() } :
        kind === "causal-engine" ? { url: causalUrl.trim() } :
        kind === "chat" ? {
          baseUrl: chatBaseUrl.trim(),
          model: chatModel.trim(),
          apiKey: chatApiKey,
          clearApiKey: chatClearKey,
          enableThinking: chatEnableThinking,
          thinkingStyle: chatThinkingStyle,
        } : {
          baseUrl: embBaseUrl.trim(),
          model: embModel.trim(),
          apiKey: embApiKey,
          clearApiKey: embClearKey,
        }
      const res = await api<ProbeResult>(`/service-settings/test/${kind}`, {
        method: "POST",
        body: JSON.stringify(body),
      })
      setProbes((prev) => ({ ...prev, [kind]: res }))
    } catch (err) {
      setProbes((prev) => ({
        ...prev,
        [kind]: { ok: false, latencyMs: 0, message: "测试请求失败", detail: errorMessage(err) },
      }))
    } finally {
      setProbing(null)
    }
  }

  const data = query.data

  return <>
    <SectionHeader
      title="服务配置"
      description="热更新 MinerU 解析地址、因果抽取引擎开关与地址、对话大模型及嵌入模型（支持云端 API Key 与本地免密部署），保存后立即生效，无需重启服务。"
      actions={<>
        <Button asChild variant="outline">
          <a href="/system-status"><Activity size={15} aria-hidden="true" />查看系统状态</a>
        </Button>
        <Button type="button" variant="outline" onClick={query.reload} disabled={query.loading}>
          <RefreshCw size={15} className={query.loading ? "animate-spin" : undefined} aria-hidden="true" />刷新
        </Button>
      </>}
    />

    {error && <div className="mb-4"><Notice error>{error}</Notice></div>}
    {saveNotice && <div className="mb-4"><Notice>{saveNotice}</Notice></div>}

    {query.error ? (
      <Notice error>{query.error.message}</Notice>
    ) : !data ? (
      <Loading label="正在加载服务配置…" />
    ) : (
      <form onSubmit={handleSave} className="grid gap-5">
        <section className="panel">
          <div className="panel-bar">
            <div className="panel-title"><Server size={16} strokeWidth={1.7} aria-hidden="true" /><strong>MinerU 解析服务</strong></div>
            <Button type="button" variant="outline" size="sm" disabled={probing === "mineru"} onClick={() => runProbe("mineru")}>
              <PlugZap size={14} aria-hidden="true" />{probing === "mineru" ? "测试中…" : "测试 MinerU 连接"}
            </Button>
          </div>
          <div className="causal-create-body">
            <div className="causal-create-row">
              <label>
                <span>服务地址（含端口）</span>
                <input className="field mono" value={mineruUrl} onChange={(e) => setMineruUrl(e.target.value)} placeholder="http://127.0.0.1:18002" required />
              </label>
              <label>
                <span>默认解析档位</span>
                <select className="field" value={mineruTier} onChange={(e) => setMineruTier(e.target.value)}>
                  <option value="">默认（由服务端决定）</option>
                  <option value="flash">flash</option>
                  <option value="basic">basic</option>
                  <option value="standard">standard</option>
                  <option value="advanced">advanced</option>
                </select>
              </label>
            </div>
            <div className="causal-create-row">
              <label>
                <span>解析超时时间（秒）</span>
                <input className="field tabular-nums" type="number" min={10} max={86400} value={mineruTimeout} onChange={(e) => setMineruTimeout(e.target.value)} />
              </label>
              <div className="flex items-end text-sm muted">
                容器端口变更时直接修改地址并保存即可，后台任务与系统状态探针立即切换。
              </div>
            </div>
            <ProbeBanner result={probes["mineru"]} />
          </div>
        </section>

        <section className="panel">
          <div className="panel-bar">
            <div className="panel-title"><Sparkles size={16} strokeWidth={1.7} aria-hidden="true" /><strong>因果抽取引擎（Causal Engine）</strong></div>
            <Button type="button" variant="outline" size="sm" disabled={probing === "causal-engine"} onClick={() => runProbe("causal-engine")}>
              <PlugZap size={14} aria-hidden="true" />{probing === "causal-engine" ? "测试中…" : "测试引擎连接"}
            </Button>
          </div>
          <div className="causal-create-body">
            <div className="flex flex-wrap items-center justify-between gap-3 p-3 rounded border" style={{ background: "var(--surface-soft, #f8fafc)" }}>
              <div>
                <div className="font-medium text-sm">启用因果抽取功能</div>
                <div className="text-xs muted mt-0.5">开启后允许在论文库、论文详情与因果抽取中心发起 LLM 因果抽取任务。</div>
              </div>
              <label className="flex items-center gap-2 cursor-pointer" style={{ display: "inline-flex" }}>
                <input type="checkbox" checked={causalEnabled} onChange={(e) => setCausalEnabled(e.target.checked)} />
                <span className="text-sm font-medium">{causalEnabled ? "已启用" : "已关闭"}</span>
              </label>
            </div>
            <div className="causal-create-row">
              <label>
                <span>引擎地址（含端口）</span>
                <input className="field mono" value={causalUrl} onChange={(e) => setCausalUrl(e.target.value)} placeholder="http://127.0.0.1:18003" required />
              </label>
              <label>
                <span>默认入库模式</span>
                <select className="field" value={causalDefaultMode} onChange={(e) => setCausalDefaultMode(e.target.value as "RAW" | "STANDARDIZED")}>
                  <option value="RAW">原始变量（RAW，默认）</option>
                  <option value="STANDARDIZED">STW 标准变量（STANDARDIZED）</option>
                </select>
              </label>
            </div>
            <div className="causal-create-row">
              <label>
                <span>单篇模型调用上限</span>
                <input className="field tabular-nums" type="number" min={1} max={50000} value={causalMaxCalls} onChange={(e) => setCausalMaxCalls(e.target.value)} />
              </label>
              <div className="flex items-end text-sm muted">
                通过 <code className="mono">docker compose up -d</code> 即可自动拉起引擎，无需在 <code className="mono">.env</code> 预置模型密钥。
              </div>
            </div>
            <ProbeBanner result={probes["causal-engine"]} />
          </div>
        </section>

        <section className="panel">
          <div className="panel-bar">
            <div className="panel-title"><Bot size={16} strokeWidth={1.7} aria-hidden="true" /><strong>对话大模型（Chat LLM）</strong></div>
            <Button type="button" variant="outline" size="sm" disabled={probing === "chat"} onClick={() => runProbe("chat")}>
              <PlugZap size={14} aria-hidden="true" />{probing === "chat" ? "测试中…" : "测试对话模型"}
            </Button>
          </div>
          <div className="causal-create-body">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-xs muted">快捷预设：</span>
              {CHAT_PRESETS.map((preset) => (
                <Button key={preset.label} type="button" variant="outline" size="sm" onClick={() => {
                  setChatBaseUrl(preset.baseUrl)
                  setChatModel(preset.model)
                  setChatThinkingStyle(preset.style)
                  setChatEnableThinking(false)
                  if (preset.keyless) { setChatApiKey(""); setChatClearKey(true) }
                }}>
                  {preset.label}
                </Button>
              ))}
            </div>
            <div className="causal-create-row">
              <label>
                <span>OpenAI 兼容 Base URL</span>
                <input className="field mono" value={chatBaseUrl} onChange={(e) => setChatBaseUrl(e.target.value)} placeholder="https://api.deepseek.com/v1 或 http://host.docker.internal:8000/v1" />
              </label>
              <label>
                <span>模型名称（Model ID）</span>
                <input className="field mono" value={chatModel} onChange={(e) => setChatModel(e.target.value)} placeholder="deepseek-chat / qwen3-max / 本地模型名" />
              </label>
            </div>
            <div className="causal-create-row">
              <label>
                <span>API Key（本地部署无密钥时可留空）</span>
                <div className="flex gap-2">
                  <input
                    className="field mono flex-1"
                    type="password"
                    autoComplete="new-password"
                    value={chatApiKey}
                    onChange={(e) => { setChatApiKey(e.target.value); if (e.target.value) setChatClearKey(false) }}
                    placeholder={chatClearKey ? "已标记清空，保存后切换为免密本地模式" : data.chat.apiKeyConfigured ? `已配置（${data.chat.apiKeyMask}），留空保持不变` : "未配置密钥（免密本地模型可留空）"}
                  />
                  {(data.chat.apiKeyConfigured || chatApiKey) && !chatClearKey && (
                    <Button type="button" variant="outline" size="sm" onClick={() => { setChatApiKey(""); setChatClearKey(true) }} title="清空密钥（切换为本地免密模型）">
                      <Eraser size={14} aria-hidden="true" />清空
                    </Button>
                  )}
                </div>
              </label>
              <label>
                <span>思考模式控制协议</span>
                <select className="field" value={chatThinkingStyle} onChange={(e) => setChatThinkingStyle(e.target.value as ThinkingStyle)}>
                  {THINKING_STYLES.map((opt) => <option key={opt.value} value={opt.value}>{opt.label}</option>)}
                </select>
              </label>
            </div>
            <div className="flex flex-wrap items-center justify-between gap-3 p-3 rounded border" style={{ background: "var(--surface-soft, #f8fafc)" }}>
              <div>
                <div className="font-medium text-sm flex items-center gap-1.5">
                  <Sliders size={14} aria-hidden="true" />思考模式（默认关闭）
                </div>
                <div className="text-xs muted mt-0.5">
                  统一适配 DeepSeek（<code className="mono">thinking.type</code>）、百炼/硅基流动（<code className="mono">enable_thinking</code>）、本地 vLLM（<code className="mono">chat_template_kwargs</code>）、Ollama（<code className="mono">think</code>）及 OpenAI 推理模型。
                  {chatThinkingStyle === "AUTO" && <> 当前生效协议：<code className="mono">{data.chat.effectiveThinkingStyle}</code></>}
                </div>
              </div>
              <label className="flex items-center gap-2 cursor-pointer" style={{ display: "inline-flex" }}>
                <input type="checkbox" checked={chatEnableThinking} onChange={(e) => setChatEnableThinking(e.target.checked)} />
                <span className="text-sm font-medium">{chatEnableThinking ? "已开启思考" : "已关闭思考（推荐）"}</span>
              </label>
            </div>
            <ProbeBanner result={probes["chat"]} />
          </div>
        </section>

        <section className="panel">
          <div className="panel-bar">
            <div className="panel-title"><Cpu size={16} strokeWidth={1.7} aria-hidden="true" /><strong>嵌入模型（Embedding Model）</strong></div>
            <Button type="button" variant="outline" size="sm" disabled={probing === "embedding"} onClick={() => runProbe("embedding")}>
              <PlugZap size={14} aria-hidden="true" />{probing === "embedding" ? "测试中…" : "测试嵌入模型"}
            </Button>
          </div>
          <div className="causal-create-body">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-xs muted">快捷预设：</span>
              {EMBEDDING_PRESETS.map((preset) => (
                <Button key={preset.label} type="button" variant="outline" size="sm" onClick={() => {
                  setEmbBaseUrl(preset.baseUrl)
                  setEmbModel(preset.model)
                  if (preset.keyless) { setEmbApiKey(""); setEmbClearKey(true) }
                }}>
                  {preset.label}
                </Button>
              ))}
            </div>
            <div className="causal-create-row">
              <label>
                <span>OpenAI 兼容 Base URL</span>
                <input className="field mono" value={embBaseUrl} onChange={(e) => setEmbBaseUrl(e.target.value)} placeholder="https://api.siliconflow.cn/v1 或 http://host.docker.internal:8001/v1" />
              </label>
              <label>
                <span>嵌入模型名称（Model ID）</span>
                <input className="field mono" value={embModel} onChange={(e) => setEmbModel(e.target.value)} placeholder="BAAI/bge-m3 / text-embedding-v4" />
              </label>
            </div>
            <div className="causal-create-row">
              <label>
                <span>API Key（本地部署无密钥时可留空）</span>
                <div className="flex gap-2">
                  <input
                    className="field mono flex-1"
                    type="password"
                    autoComplete="new-password"
                    value={embApiKey}
                    onChange={(e) => { setEmbApiKey(e.target.value); if (e.target.value) setEmbClearKey(false) }}
                    placeholder={embClearKey ? "已标记清空，保存后切换为免密本地模式" : data.embedding.apiKeyConfigured ? `已配置（${data.embedding.apiKeyMask}），留空保持不变` : "未配置密钥（免密本地模型可留空）"}
                  />
                  {(data.embedding.apiKeyConfigured || embApiKey) && !embClearKey && (
                    <Button type="button" variant="outline" size="sm" onClick={() => { setEmbApiKey(""); setEmbClearKey(true) }} title="清空密钥（切换为本地免密模型）">
                      <Eraser size={14} aria-hidden="true" />清空
                    </Button>
                  )}
                </div>
              </label>
              <div className="flex items-end text-sm muted">
                若引擎运行在 Docker 容器中且调用宿主机本地模型，主机请填写 <code className="mono">host.docker.internal</code>。
              </div>
            </div>
            <ProbeBanner result={probes["embedding"]} />
          </div>
        </section>

        <div className="panel p-4 flex flex-wrap items-center justify-between gap-3">
          <div className="text-sm muted">
            {data.updatedAt
              ? <>上次保存：{dateLabel(data.updatedAt)}{data.updatedBy ? ` · 操作人 ${data.updatedBy}` : ""}</>
              : "当前使用环境变量默认配置，尚未在管理台持久化覆盖。"}
          </div>
          <Button type="submit" disabled={saving}>
            <Save size={15} aria-hidden="true" />{saving ? "正在保存…" : "保存配置并立即生效"}
          </Button>
        </div>
      </form>
    )}
  </>
}

function ProbeBanner({ result }: { result?: ProbeResult }) {
  if (!result) return null
  return (
    <div className={"p-3 rounded border text-sm flex items-start gap-2 " + (result.ok ? "bg-emerald-50 border-emerald-200 text-emerald-900" : "bg-red-50 border-red-200 text-red-900")}>
      {result.ok
        ? <CheckCircle2 size={16} className="shrink-0 mt-0.5 text-emerald-600" aria-hidden="true" />
        : <XCircle size={16} className="shrink-0 mt-0.5 text-red-600" aria-hidden="true" />}
      <div className="min-w-0">
        <div className="font-medium">
          {result.message}{result.latencyMs > 0 && <span className="ml-2 text-xs opacity-75 tabular-nums">{result.latencyMs} ms</span>}
        </div>
        {result.detail && <div className="text-xs mt-1 break-all opacity-90">{result.detail}</div>}
      </div>
    </div>
  )
}
