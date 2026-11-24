import { StrictMode, useEffect, useState } from "react"
import { createRoot } from "react-dom/client"
import { api, ApiError, useQuery } from "./api"
import { AuditLog } from "./audit"
import { CausalExtractionPage } from "./causal/extraction"
import { CausalExtractionDetailPage } from "./causal/extraction-detail"
import { CausalFieldsPage } from "./causal/fields"
import { CausalGraphPage } from "./causal/graph"
import { CausalPaperClaimsPage } from "./causal/paper-claims"
import { CausalRelationDetailPage } from "./causal/relation-detail"
import { CausalVariableDetailPage } from "./causal/variable-detail"
import { Empty, Loading } from "./components/shared"
import { Workspace } from "./components/workspace"
import { Journals } from "./journals"
import { FirstPassword, Login } from "./login"
import { Jobs, JobDetail } from "./jobs"
import { OpenAlex } from "./openalex"
import { Overview } from "./overview"
import { Library } from "./papers/library"
import { PaperDetail } from "./papers/detail"
import { Permissions, type Session } from "./permissions"
import { Roles } from "./roles"
import { useLinkInterceptor, useLocation } from "./router"
import { SystemStatus } from "./system-status"
import { ResetPasswordNotice, Users, type IssuedPassword } from "./users"
import "./styles.css"

function renderCausalGraphRoute(sub: string | undefined, param: string | undefined, search: string) {
  if (!sub) return <CausalGraphPage />
  if (sub === "nodes" && param) return <CausalVariableDetailPage key={param} variable={decodeURIComponent(param)} />
  if (sub === "edges" && !param) {
    const sp = new URLSearchParams(search)
    return <CausalRelationDetailPage key={search} cause={sp.get("cause") ?? undefined} effect={sp.get("effect") ?? undefined} />
  }
  if (sub === "relations" && param) return <CausalRelationDetailPage key={param} claimId={decodeURIComponent(param)} />
  if (sub === "papers" && param) return <CausalPaperClaimsPage key={param} workId={decodeURIComponent(param)} />
  return <Empty title="找不到此图谱视图"><a href="/causal-graph" className="inline-link">返回因果图谱</a></Empty>
}

function App() {
  useLinkInterceptor()
  const location = useLocation()
  const session = useQuery<Session>("/session")
  const [reset, setReset] = useState<IssuedPassword>()
  const refresh = session.reload
  useEffect(() => {
    window.addEventListener("focus", refresh)
    window.addEventListener("sessionchange", refresh)
    return () => {
      window.removeEventListener("focus", refresh)
      window.removeEventListener("sessionchange", refresh)
    }
  }, [refresh])
  const resetNotice = reset && <ResetPasswordNotice issued={reset} close={() => setReset(undefined)} />
  const signedOut = session.error instanceof ApiError && session.error.status === 401
  if (session.loading && !session.data) return <div className="boot-screen"><Loading label="正在打开 AI Economist…" /></div>
  if (!session.data || signedOut) {
    const showError = session.error && !signedOut
    return <><Login serviceError={showError ? session.error?.message : undefined} />{resetNotice}</>
  }
  const path = location.pathname.replace(/\/$/, "") || "/overview"
  const [section, id, action] = path.slice(1).split("/")
  async function logout() {
    try { await api("/session", { method: "DELETE" }); window.location.assign("/") }
    catch { window.location.reload() }
  }
  if (session.data.mustChangePassword) return <FirstPassword logout={logout} />

  const permissions = session.data.permissions
  const required = section === "papers" || section === "journals" ? "PAPER_READ" :
    section === "jobs" ? "JOB_READ" :
    section === "openalex" ? "OPENALEX_MANAGE" :
    ["causal-graph", "causal-fields", "causal-extraction"].includes(section) ? "CAUSAL_READ" :
    ["system-status", "users", "roles", "audit"].includes(section) ? "SYSTEM_MANAGE" : undefined
  const blocked = (required && !permissions.includes(required)) ||
    (section === "openalex" && !permissions.includes("JOB_READ"))
  const page = blocked ? <section className="empty-state">
    <h1>无权访问此页面</h1><a href="/overview" className="inline-link">返回总览</a>
  </section> :
    section === "overview" && !id ? <Overview /> :
    section === "journals" && !id ? <Journals /> :
    section === "papers" ? id ? <PaperDetail key={id} id={decodeURIComponent(id)} /> : <Library /> :
    section === "jobs" ? id ? <JobDetail key={id} id={decodeURIComponent(id)} /> : <Jobs /> :
    section === "openalex" ? <OpenAlex /> :
    section === "causal-graph" ? renderCausalGraphRoute(id, action, location.search) :
    section === "causal-fields" && !id ? <CausalFieldsPage /> :
    section === "causal-extraction" ? id ? <CausalExtractionDetailPage key={id} batchId={decodeURIComponent(id)} /> : <CausalExtractionPage /> :
    section === "system-status" && !id ? <SystemStatus /> :
    section === "users" && !id ? <Users onChange={refresh} onPasswordReset={(issued) => { setReset(issued); refresh() }} /> :
    section === "roles" ? <Roles key={path} id={id && id !== "new" ? decodeURIComponent(id) : undefined} editing={id === "new" || action === "edit"} /> :
    section === "audit" && !id ? <AuditLog /> : <Empty title="找不到此页面"><a href="/overview" className="inline-link">返回总览</a></Empty>
  return <><Permissions.Provider value={permissions}>
    <Workspace page={path} displayName={session.data.displayName} logout={logout}>{page}</Workspace>
  </Permissions.Provider>{resetNotice}</>
}

createRoot(document.getElementById("root")!).render(<StrictMode><App /></StrictMode>)
