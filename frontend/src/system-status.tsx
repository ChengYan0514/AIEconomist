import { RefreshCw, Server } from "lucide-react"
import { useQuery } from "./api"
import { Notice } from "./components/shared"
import { Button } from "./components/ui/button"
import { SectionHeader } from "./components/workspace"

const dependencies = [
  ["POSTGRESQL", "PostgreSQL"],
  ["MINERU", "MinerU"],
  ["CURRENT_STORAGE", "新版文件存储"],
  ["LEGACY_STORAGE", "旧版文件存储"],
  ["OPENALEX_SNAPSHOT", "OpenAlex 快照"],
] as const

const states = {
  HEALTHY: ["正常", "green"],
  UNAVAILABLE: ["不可用", "red"],
  NOT_CHECKED: ["未检查", "neutral"],
}

type SystemReport = {
  checks: {
    dependency: typeof dependencies[number][0]
    state: keyof typeof states
    checkedAt: string | null
    message: string
  }[]
  configuration: {
    version: string
    maxOriginalBytes: number
    maxZipBytes: number
    maxJobTargets: number
  }
}

const checkTime = new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "medium", hour12: false })

export function SystemStatus() {
  const { data, error, loading, reload } = useQuery<SystemReport>("/system-status", 8000)
  const results = new Map(data?.checks.map((check) => [check.dependency, check]))
  const lastCheckedAt = data?.checks.reduce<string | null>((latest, { checkedAt }) =>
    checkedAt && (!latest || Date.parse(checkedAt) > Date.parse(latest)) ? checkedAt : latest, null)
  const configuration = data?.configuration

  return <>
    <SectionHeader title="系统状态" description="核验数据库、MinerU 解析服务、文件存储与 OpenAlex 快照的实时可用性。"
      actions={<Button type="button" onClick={reload} disabled={loading}>
        <RefreshCw size={15} className={loading ? "animate-spin" : undefined} aria-hidden="true" />
        {loading ? "正在检查…" : "重新检查"}
      </Button>} />
    {error && <div className="mb-4"><Notice error>{data ? "刷新失败，当前显示上次检查结果。" : "检查失败，请稍后重试。"}</Notice></div>}
    <section className="panel" aria-busy={loading}>
      <div className="panel-bar">
        <div className="panel-title"><Server size={17} strokeWidth={1.7} aria-hidden="true" /><strong>核心服务依赖</strong></div>
        <span className="muted text-sm tabular-nums" aria-live="polite">
          {lastCheckedAt ? <>上次检查 <CheckedTime value={lastCheckedAt} /></> : "尚未取得检查结果"}
        </span>
      </div>
      <div className="table-scroll" role="region" aria-label="依赖状态" tabIndex={0}>
        <table className="data-table system-status-table" aria-label="依赖检查结果">
          <thead><tr><th scope="col">依赖</th><th scope="col">状态</th><th scope="col">检查结果</th><th scope="col">检查时间</th></tr></thead>
          <tbody>{dependencies.map(([dependency, name]) => {
            const result = results.get(dependency)
            const [label, color] = states[result?.state ?? "NOT_CHECKED"]
            return <tr key={dependency}>
              <th scope="row">{name}</th>
              <td><span className={"status-chip status-" + color}><span className="status-dot" aria-hidden="true" />{label}</span></td>
              <td>{result?.message ?? "—"}</td>
              <td className="table-date">{result?.checkedAt ? <CheckedTime value={result.checkedAt} /> : "—"}</td>
            </tr>
          })}</tbody>
        </table>
      </div>
    </section>
    <section className="mt-7" aria-labelledby="effective-configuration">
      <div className="overview-heading">
        <h2 id="effective-configuration">有效配置</h2>
        <span>当前部署生效的只读配额与版本信息</span>
      </div>
      <dl className="panel system-status-configuration">
        <div className="config-cell"><dt>应用版本</dt><dd className="mono">{configuration?.version ?? "—"}</dd></div>
        <div className="config-cell"><dt>单文件上限</dt><dd className="tabular-nums">{byteLimit(configuration?.maxOriginalBytes)}</dd></div>
        <div className="config-cell"><dt>ZIP 上限</dt><dd className="tabular-nums">{byteLimit(configuration?.maxZipBytes)}</dd></div>
        <div className="config-cell"><dt>每任务论文上限</dt><dd className="tabular-nums">{configuration ? configuration.maxJobTargets.toLocaleString("zh-CN") + " 篇" : "—"}</dd></div>
      </dl>
    </section>
  </>
}

function CheckedTime({ value }: { value: string }) {
  return <time dateTime={value} title={value}>{checkTime.format(new Date(value))}</time>
}

function byteLimit(bytes?: number) {
  if (bytes === undefined) return "—"
  const mebibytes = bytes / (1024 * 1024)
  return Number.isInteger(mebibytes) ? mebibytes.toLocaleString("zh-CN") + " MiB" : bytes.toLocaleString("zh-CN") + " 字节"
}
