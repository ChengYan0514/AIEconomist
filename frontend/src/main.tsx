import { StrictMode } from "react"
import { createRoot } from "react-dom/client"
import { api, ApiError, useQuery } from "./api"
import { Empty, Loading } from "./components/shared"
import { Workspace } from "./components/workspace"
import { Login } from "./login"
import { Jobs, JobDetail } from "./jobs"
import { OpenAlex } from "./openalex"
import { Library } from "./papers/library"
import { PaperDetail } from "./papers/detail"
import "./styles.css"

function App() {
  const session = useQuery<{ username: string }>("/session")
  if (session.loading && !session.data) return <div className="boot-screen"><Loading label="正在打开 AI Economist…" /></div>
  if (!session.data) {
    const showError = session.error && (!(session.error instanceof ApiError) || session.error.status !== 401)
    return <Login serviceError={showError ? session.error?.message : undefined} />
  }
  const path = window.location.pathname.replace(/\/$/, "") || "/papers"
  const [section, id] = path.slice(1).split("/")
  async function logout() {
    try { await api("/session", { method: "DELETE" }); window.location.assign("/") }
    catch { window.location.reload() }
  }
  const page = section === "papers" ? id ? <PaperDetail id={decodeURIComponent(id)} /> : <Library /> :
    section === "jobs" ? id ? <JobDetail id={decodeURIComponent(id)} /> : <Jobs /> :
    section === "openalex" ? <OpenAlex /> : <Empty title="找不到此页面"><a href="/papers" className="inline-link">返回论文库</a></Empty>
  return <Workspace page={path} username={session.data.username} logout={logout}>{page}</Workspace>
}

createRoot(document.getElementById("root")!).render(<StrictMode><App /></StrictMode>)
