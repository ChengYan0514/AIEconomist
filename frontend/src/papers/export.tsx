import { Download } from "lucide-react"
import { Button } from "../components/ui/button"

export function PaperCsvExport({ query = "" }: { query?: string }) {
  return <Button asChild variant="outline"><a href={"/api/papers/export" + query}><Download size={17} />导出 CSV</a></Button>
}
