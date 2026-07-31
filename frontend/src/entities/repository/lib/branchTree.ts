export type BranchTreeNode =
    | { kind: 'folder'; label: string; path: string; children: BranchTreeNode[] }
    | { kind: 'leaf'; label: string; name: string }

interface MutableFolder {
    label: string
    path: string
    folders: Map<string, MutableFolder>
    leaves: { label: string; name: string }[]
}

/**
 * Groups slash-delimited branch/ref names into a nested folder tree
 * (SourceTree/GitKraken-style), e.g. "feature/x" and "feature/y" fold under
 * one "feature" folder. Folders sort before leaves, both alphabetically.
 */
export function buildBranchTree(names: string[]): BranchTreeNode[] {
    const root: MutableFolder = {label: '', path: '', folders: new Map(), leaves: []}
    for (const name of names) {
        const parts = name.split('/')
        let node = root
        for (let i = 0; i < parts.length - 1; i++) {
            const part = parts[i]
            let next = node.folders.get(part)
            if (next == null) {
                next = {
                    label: part,
                    path: node.path === '' ? part : `${node.path}/${part}`,
                    folders: new Map(),
                    leaves: [],
                }
                node.folders.set(part, next)
            }
            node = next
        }
        node.leaves.push({label: parts[parts.length - 1], name})
    }
    return toNodes(root)
}

function toNodes(folder: MutableFolder): BranchTreeNode[] {
    const folders: BranchTreeNode[] = [...folder.folders.values()]
        .sort((a, b) => a.label.localeCompare(b.label))
        .map((f) => ({kind: 'folder', label: f.label, path: f.path, children: toNodes(f)}))
    const leaves: BranchTreeNode[] = [...folder.leaves]
        .sort((a, b) => a.label.localeCompare(b.label))
        .map((leaf) => ({kind: 'leaf', label: leaf.label, name: leaf.name}))
    return [...folders, ...leaves]
}
