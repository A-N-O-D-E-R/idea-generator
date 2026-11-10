// API payloads, as served by the backend (upstream server/app.ts, db.ts, engine.ts).

export type Kind = 'seed' | 'expand' | 'combine' | 'guest'
export type Lang = 'sv' | 'en'
export type Role = 'distill' | 'expand' | 'combine' | 'judge' | 'summary' | 'research' | 'x' | 'brief'
export type ProviderId = 'openrouter' | 'anthropic' | 'openai' | 'azure' | 'ollama' | 'mlx' | 'lmstudio' | 'custom'
export type LeadSource = 'x' | 'research'

export type Idea = {
  id: number
  boardId: number
  title: string
  pitch: string
  kind: Kind
  sourceUrl: string | null
  x: number
  y: number
  originality: number | null
  feasibility: number | null
  impact: number | null
  reason: string | null
  judgedAt: string | null
  medalOverride: number | null
  generation: number
  projectPath: string | null
  originIdeaId: number | null
  originBoardId: number | null
  originBoardTitle: string | null
  keywords: string[]
  createdAt: string
  /** Elo from pairwise duels; null = never duelled */
  elo: number | null
  duels: number
}

export type Edge = { from: number; to: number }

export type Flow = { system: string; expand: string; combine: string }

export type BoardConfig = {
  flowName: string
  flow: Flow
  judgeGuidance: string
  researchers: Record<LeadSource, { enabled: boolean; focus: string }>
  lang: Lang
  mode: 'manual' | 'auto'
  autopilotSeconds: number
  rate: { stepsPerHour: number; usdPerDay: number }
  autoProjectOnGold: boolean
  models: Partial<Record<Role, string>>
}

export type BoardSummary = {
  headline: string
  themes: { name: string; insight: string; ideas: string[] }[]
  strongest: { title: string; why: string }[]
  gaps: string[]
  nextSteps: string[]
  ideaCount: number
  lang: string
  createdAt: string
}

export type Board = {
  id: number
  title: string
  goal: string
  config: BoardConfig
  createdAt: string
  updatedAt: string
  originIdeaId: number | null
  originIdeaTitle: string | null
  originBoardId: number | null
  originBoardTitle: string | null
  summary: BoardSummary | null
  /** Only in GET /api/boards. */
  ideaCount?: number
}

export type Template = { id: number; name: string; system: string; expand: string; combine: string; sort: number }

export type Lead = {
  id: number
  boardId: number
  source: LeadSource
  title: string
  summary: string
  hook: string
  url: string | null
  meta: { published?: string; [k: string]: unknown }
  ideaId: number | null
  fetchedAt: string
}

export type Spinoff = { ideaId: number; boardId: number; title: string }

export type AutopilotStatus = {
  /** manual | running | idle | error | pace | hourly | backoff | board_budget | global_budget */
  state: string
  until?: number
  code?: string
  message?: string
  last?: string
  stepsLastHour?: number
  spentToday?: number
}

export type BoardData = {
  board: Board
  ideas: Idea[]
  edges: Edge[]
  leads: Lead[]
  spinoffs: Spinoff[]
  judging: number[]
  autopilot: AutopilotStatus
  spentToday: number
}

export type ProviderStatus = {
  id: ProviderId
  label: string
  local: boolean
  needsKey: boolean
  search: boolean
  note: string | null
  keySource: 'browser' | 'env' | null
  baseUrl: string | null
}

export type ProvidersResponse = {
  settingsReceived: boolean
  providers: ProviderStatus[]
  spentToday: number
  spentTotal: { usd: number; calls: number }
}

export type AppSettings = {
  keys: Partial<Record<ProviderId, string>>
  baseUrls: Partial<Record<ProviderId, string>>
  models: Record<Role, string>
  projectsRoot: string
  maxUsdPerDay: number
  /** Idea workspace (INDEX.md + ideas/) that projects are also filed into; '' = off */
  workspaceRoot: string
}

export type Dashboard = {
  days: string[]
  totals: { ideas: number; boards: number; keywords: number; recent: number }
  keywords: {
    keyword: string
    count: number
    boards: number
    meanSum: number | null
    recent: number
    previous: number
    series: number[]
  }[]
  boards: { id: number; title: string; keywords: { keyword: string; count: number }[] }[]
}

export type KeywordIdea = Idea & { boardTitle: string }

export type BriefIdea = {
  id: number
  boardId: number
  title: string
  pitch: string
  sum: number | null
  medal: number | null
  reason: string | null
  kind: Kind
}
export type ReportProject = BriefIdea & { boardTitle: string; path: string }

export type Report = {
  days: number
  totals: {
    boards: number
    ideas: number
    newIdeas: number
    judged: number
    medals: { gold: number; silver: number; bronze: number }
    projects: number
    spentUsd: number
  }
  projects: ReportProject[]
  boards: {
    id: number
    title: string
    goal: string
    mode: 'manual' | 'auto'
    ideaCount: number
    newIdeas: number
    best: BriefIdea[]
    summary: (BoardSummary & { stale: boolean }) | null
    projects: ReportProject[]
  }[]
  synthesis: {
    headline: string
    insights: string[]
    standouts: { title: string; board: string; why: string }[]
    opportunities: string[]
    nextSteps: string[]
    days: number
    lang: string
    createdAt: string
  } | null
}

/** Live events on GET /api/boards/:id/events. */
export type BoardEvent =
  | { type: 'hello'; autopilot: AutopilotStatus; settingsReceived: boolean }
  | { type: 'ideas'; ideas: Idea[] }
  | { type: 'edges'; edges: Edge[] }
  | { type: 'removed'; ids: number[] }
  | { type: 'judging'; ids: number[]; on: boolean }
  | { type: 'board'; board: Board }
  | { type: 'leads'; source: LeadSource; leads: Lead[] }
  | { type: 'autopilot'; status: AutopilotStatus }
  | { type: 'project'; ideaId: number; path: string; byJudge?: boolean } & WorkspaceResult
  | { type: 'spinoff'; ideaId: number; boardId: number; title: string }
  | { type: 'error'; code: string; message: string; ideaId?: number }

/** Filing into the idea workspace, added to project results when a workspace is set. */
export type WorkspaceResult = {
  workspace?: string
  workspaceExisted?: boolean
  workspaceError?: string
  /** Slug of the existing idea this one duplicates: nothing was filed. */
  workspaceDuplicateOf?: string
  /** Existing ideas it was linked to, both ways. */
  workspaceRelated?: string[]
}
