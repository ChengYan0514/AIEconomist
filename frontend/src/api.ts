import { useCallback, useEffect, useState } from "react"

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body && !(init.body instanceof FormData)) headers.set("Content-Type", "application/json")
  if (init.method && init.method !== "GET") {
    const csrf = await api<{ headerName: string; token: string }>("/csrf")
    headers.set(csrf.headerName, csrf.token)
  }
  let response: Response
  try {
    response = await fetch("/api" + path, { ...init, headers, credentials: "same-origin" })
  } catch {
    throw new ApiError(0, "无法连接管理服务，请检查连接后重试。")
  }
  if (!response.ok) {
    if (path !== "/session" && (response.status === 401 || response.status === 403)) {
      window.dispatchEvent(new Event("sessionchange"))
    }
    const error = await response.json().catch(() => null)
    throw new ApiError(response.status, error?.message || "请求暂时失败，请稍后重试。")
  }
  return response.status === 204 ? undefined as T : response.json()
}

export function useQuery<T>(path: string | null, timeoutMs?: number) {
  const [result, setResult] = useState<{ data: T; path: string }>()
  const [error, setError] = useState<Error>()
  const [loading, setLoading] = useState(Boolean(path))
  const [count, setCount] = useState(0)
  const reload = useCallback(() => setCount((value) => value + 1), [])

  useEffect(() => {
    if (!path) return
    let active = true
    setLoading(true)
    setError(undefined)
    const signal = timeoutMs === undefined ? undefined : AbortSignal.timeout(timeoutMs)
    api<T>(path, { signal }).then((data) => {
      if (active) setResult({ data, path })
    }).catch((reason: Error) => {
      if (active) setError(reason)
    }).finally(() => {
      if (active) setLoading(false)
    })
    return () => { active = false }
  }, [path, count, timeoutMs])

  return { data: result?.data, dataPath: result?.path, error, loading, reload }
}

export function params(values: Record<string, string | number | boolean | undefined>) {
  const result = new URLSearchParams()
  Object.entries(values).forEach(([key, value]) => {
    if (value !== undefined && value !== "") result.set(key, String(value))
  })
  const query = result.toString()
  return query ? "?" + query : ""
}

export function paperPath(id: string) {
  return "/papers/" + encodeURIComponent(id)
}

export function jobPath(id: string) {
  return "/jobs/" + encodeURIComponent(id)
}

export function rolePath(id: string) {
  return "/roles/" + encodeURIComponent(id)
}

export function dateLabel(value?: string | null) {
  if (!value) return "—"
  return new Intl.DateTimeFormat("zh-CN", {
    month: "short", day: "numeric", hour: "2-digit", minute: "2-digit", hour12: false,
  }).format(new Date(value))
}

export function errorMessage(reason: unknown) {
  return reason instanceof Error ? reason.message : "操作失败，请重试。"
}
