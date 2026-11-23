import { createContext, useContext } from "react"
import type { JobKind } from "./types"

export type Permission = "PAPER_READ" | "CONTENT_READ" | "PAPER_CSV_EXPORT" | "PAPER_WRITE"
  | "JOB_READ" | "PDF_PARSE" | "FULL_TEXT_IMPORT" | "OPENALEX_MANAGE" | "SYSTEM_MANAGE"

export const roles: Record<string, string> = { SYSTEM_ADMIN: "系统管理员", DATA_MAINTAINER: "数据维护员", READ_ONLY: "只读用户" }

export type Session = {
  username: string
  displayName: string
  role: string
  mustChangePassword: boolean
  permissions: Permission[]
}

const jobPermissions: Record<JobKind, Permission> = {
  ORIGINAL_IMPORT: "PAPER_WRITE", PARSE: "PDF_PARSE", PUBLISH: "FULL_TEXT_IMPORT",
  MATCH: "OPENALEX_MANAGE", METADATA: "OPENALEX_MANAGE",
}

export const Permissions = createContext<Permission[]>([])

export function usePermissions() {
  const permissions = useContext(Permissions)
  const can = (permission: Permission) => permissions.includes(permission)
  const canJob = (kind: JobKind) => can("JOB_READ") && can(jobPermissions[kind])
  return { can, canJob, canProcess: canJob("PARSE") || canJob("PUBLISH") || canJob("MATCH") }
}
