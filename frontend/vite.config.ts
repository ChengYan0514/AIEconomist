import { defineConfig, loadEnv } from "vite"
import react from "@vitejs/plugin-react"
import tailwindcss from "@tailwindcss/vite"

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, ".", "")
  const target = env.PAPERFLOW_API_TARGET || "http://127.0.0.1:8095"
  const proxy = { "/api": { target } }
  return { plugins: [react(), tailwindcss()], server: { proxy }, preview: { proxy } }
})
