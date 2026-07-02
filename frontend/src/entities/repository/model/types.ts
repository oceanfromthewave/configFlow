/**
 * Repository entity as returned by `GET /repositories`
 * (docs/07-api-design.md). Only the fields the Welcome grid needs for now;
 * extended in later milestones.
 */
export type VcsType = 'GIT' | 'SVN'

export interface RepositorySummary {
  id: string
  name: string
  localPath: string
  vcsType: VcsType
  favorite: boolean
  group?: string
  lastOpenedAt?: string
}
