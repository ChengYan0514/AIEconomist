import { useEffect, useState } from "react"

const NAVIGATE_EVENT = "aie:navigate"

export interface RouteLocation {
  pathname: string
  search: string
  href: string
}

function readLocation(): RouteLocation {
  return {
    pathname: window.location.pathname,
    search: window.location.search,
    href: window.location.pathname + window.location.search,
  }
}

export function navigate(to: string, options?: { replace?: boolean }) {
  const url = new URL(to, window.location.origin)
  if (url.origin !== window.location.origin) {
    window.location.assign(url.href)
    return
  }
  const previousPath = window.location.pathname
  const target = url.pathname + url.search + url.hash
  if (target === window.location.pathname + window.location.search + window.location.hash) return
  if (options?.replace) window.history.replaceState(window.history.state, "", target)
  else window.history.pushState(null, "", target)
  if (url.pathname !== previousPath) window.scrollTo({ top: 0, behavior: "instant" as ScrollBehavior })
  window.dispatchEvent(new Event(NAVIGATE_EVENT))
}

export function useLocation(): RouteLocation {
  const [loc, setLoc] = useState<RouteLocation>(readLocation)
  useEffect(() => {
    const sync = () => setLoc(readLocation())
    window.addEventListener("popstate", sync)
    window.addEventListener(NAVIGATE_EVENT, sync)
    return () => {
      window.removeEventListener("popstate", sync)
      window.removeEventListener(NAVIGATE_EVENT, sync)
    }
  }, [])
  return loc
}

export function useLinkInterceptor() {
  useEffect(() => {
    function handleClick(event: MouseEvent) {
      if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
        return
      }
      const target = event.target as HTMLElement | null
      const anchor = target?.closest("a[href]") as HTMLAnchorElement | null
      if (!anchor) return
      if (anchor.target && anchor.target !== "_self") return
      if (anchor.hasAttribute("download")) return
      const href = anchor.getAttribute("href")
      if (!href || href.startsWith("#") || href.startsWith("mailto:") || href.startsWith("tel:")) return
      const url = new URL(anchor.href, window.location.origin)
      if (url.origin !== window.location.origin) return
      if (url.pathname.startsWith("/api/")) return
      event.preventDefault()
      navigate(url.pathname + url.search + url.hash)
    }
    document.addEventListener("click", handleClick)
    return () => document.removeEventListener("click", handleClick)
  }, [])
}
