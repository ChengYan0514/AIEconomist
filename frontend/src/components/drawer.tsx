import { X } from "lucide-react"
import { useEffect, useId, useRef, type ReactNode } from "react"
import { Button } from "./ui/button"

interface DrawerProps {
  title: string
  subtitle?: ReactNode
  headerExtra?: ReactNode
  footer?: ReactNode
  children: ReactNode
  onClose: () => void
  width?: "md" | "lg"
}

export function Drawer({ title, subtitle, headerExtra, footer, children, onClose, width = "md" }: DrawerProps) {
  const titleId = useId()
  const closeRef = useRef<HTMLButtonElement>(null)
  const returnFocus = useRef<HTMLElement | null>(null)

  useEffect(() => {
    returnFocus.current = document.activeElement as HTMLElement | null
    closeRef.current?.focus()
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = "hidden"
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape" && !document.querySelector("dialog[open]")) {
        event.preventDefault()
        onClose()
      }
    }
    window.addEventListener("keydown", onKeyDown)
    return () => {
      document.body.style.overflow = previousOverflow
      window.removeEventListener("keydown", onKeyDown)
      returnFocus.current?.focus?.()
    }
  }, [onClose])

  return <div className="drawer-backdrop" onClick={(event) => { if (event.target === event.currentTarget) onClose() }}>
    <aside className={"drawer drawer-" + width} role="dialog" aria-modal="true" aria-labelledby={titleId}>
      <header className="drawer-header">
        <div className="drawer-heading">
          <h2 id={titleId}>{title}</h2>
          {subtitle && <div className="drawer-subtitle">{subtitle}</div>}
        </div>
        <div className="drawer-header-actions">
          {headerExtra}
          <Button ref={closeRef} type="button" variant="ghost" size="icon" aria-label="关闭抽屉" onClick={onClose}>
            <X size={18} />
          </Button>
        </div>
      </header>
      <div className="drawer-body">{children}</div>
      {footer && <footer className="drawer-footer">{footer}</footer>}
    </aside>
  </div>
}
