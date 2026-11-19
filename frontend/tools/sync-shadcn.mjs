// Fetch official Radix Nova source, preserving registry response + hashes for audit.
// Not an alternative component implementation. Only cn import resolution is adapted.
import { mkdir, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
const components = ['button', 'input', 'badge', 'card', 'table', 'sheet', 'label', 'separator']
await mkdir('vendor/shadcn', { recursive: true })
await mkdir('src/components/ui', { recursive: true })
const records = []
for (const name of components) {
  const url = `https://ui.shadcn.com/r/styles/radix-nova/${name}.json`
  const response = await fetch(url)
  if (!response.ok) throw new Error(`${response.status}: ${url}`)
  const raw = await response.text()
  const item = JSON.parse(raw)
  await writeFile(`vendor/shadcn/${name}.json`, raw)
  for (const file of item.files.filter(f => f.type === 'registry:ui')) {
    const content = file.content
      .replaceAll('from "cn"', 'from "@/lib/utils"')
      .replaceAll('@/registry/radix-nova/ui/', '@/components/ui/')
      .replace('import { IconPlaceholder } from "@/app/(create)/components/icon-placeholder"', 'import { XIcon } from "lucide-react"')
      .replace(/<IconPlaceholder[\s\S]*?\/>/g, '<XIcon />')
      .replace('>Close</span>', '>关闭</span>')
    const path = `src/components/ui/${file.path.split('/').at(-1)}`
    await writeFile(path, content)
    records.push({ name, url, upstream_path: file.path, output: path, registry_sha256: createHash('sha256').update(raw).digest('hex'), source_sha256: createHash('sha256').update(file.content).digest('hex'), output_sha256: createHash('sha256').update(content).digest('hex'), dependencies: item.dependencies, registryDependencies: item.registryDependencies })
  }
}
await writeFile('vendor/shadcn/provenance.json', JSON.stringify({ retrieved_at: new Date().toISOString(), style: 'radix-nova', transformations: ['cn import → @/lib/utils (clsx + tailwind-merge)', '@/registry/radix-nova/ui → @/components/ui', 'IconPlaceholder XIcon → lucide-react XIcon', 'Close screen-reader label → 关闭'], records }, null, 2) + '\n')
console.log('Fetched official components:', records.map(r => r.name).join(', '))
