import { StrictMode, useEffect } from "react"
import { createRoot } from "react-dom/client"
import { api, ApiError, useQuery } from "./api"
import { AuditLog } from "./audit"
import { Empty, Loading } from "./components/shared"
import { Workspace } from "./components/workspace"
import { FirstPassword, Login } from "./login"
import { Jobs, JobDetail } from "./jobs"
import { OpenAlex } from "./openalex"
import { Library } from "./papers/library"
import { PaperDetail } from "./papers/detail"
import { Permissions, type Session } from "./permissions"
import { Users } from "./users"
import "./styles.css"

function App() {
  const session = useQuery<Session>("/session")
  const refresh = session.reload
  useEffect(() => {
    window.addEventListener("focus", refresh)
    window.addEventListener("sessionchange", refresh)
    return () => {
      window.removeEventListener("focus", refresh)
      window.removeEventListener("sessionchange", refresh)
    }
  }, [refresh])
  const signedOut = session.error instanceof ApiError && session.error.status === 401
  if (session.loading && !session.data) return <div className="boot-screen"><Loading label="正在打开 AI Economist…" /></div>
  if (!session.data || signedOut) {
    const showError = session.error && !signedOut
    return <Login serviceError={showError ? session.error?.message : undefined} />
  }
  const path = window.location.pathname.replace(/\/$/, "") || "/papers"
  const [section, id] = path.slice(1).split("/")
  async function logout() {
    try { await api("/session", { method: "DELETE" }); window.location.assign("/") }
    catch { window.location.reload() }
  }
  if (session.data.mustChangePassword) return <FirstPassword logout={logout} />

  const permissions = session.data.permissions
  const required = section === "papers" ? "PAPER_READ" :
    section === "jobs" ? "JOB_READ" :
    section === "openalex" ? "OPENALEX_MANAGE" :
    section === "users" || section === "audit" ? "SYSTEM_MANAGE" : undefined
  const blocked = (required && !permissions.includes(required)) ||
    (section === "openalex" && !permissions.includes("JOB_READ"))
  const page = blocked ? <section className="empty-state">
    <h1>无权访问此页面</h1><a href="/papers" className="inline-link">返回论文库</a>
  </section> :
    section === "papers" ? id ? <PaperDetail id={decodeURIComponent(id)} /> : <Library /> :
    section === "jobs" ? id ? <JobDetail id={decodeURIComponent(id)} /> : <Jobs /> :
    section === "openalex" ? <OpenAlex /> :
    section === "users" && !id ? <Users onChange={refresh} /> :
    section === "audit" && !id ? <AuditLog /> : <Empty title="找不到此页面"><a href="/papers" className="inline-link">返回论文库</a></Empty>
  return <Permissions.Provider value={permissions}>
    <Workspace page={path} displayName={session.data.displayName} logout={logout}>{page}</Workspace>
  </Permissions.Provider>
}

createRoot(document.getElementById("root")!).render(<StrictMode><App /></StrictMode>)
