// Scoring shared by the server, the browser and the tests. Pure, no DOM.
//
// The judge scores three criteria 1–5. The medal comes from the sum (3–15)
// in code, never from the model, so the thresholds stay put when the prompt
// changes. Gold must be rare: an uncalibrated LLM jury gave 3/3 to the bare
// word "fire", which makes "gold" meaningless.

export const CRITERIA = ['originality', 'feasibility', 'impact'] as const
export type Criterion = (typeof CRITERIA)[number]

export type Scored = Partial<Record<Criterion, number | null>> & { medalOverride?: number | null }

// Medal index: 0 none, 1 bronze, 2 silver, 3 gold.
export const MEDAL_MIN_SUM = { 1: 9, 2: 11, 3: 13 } as const

export function clampScore(n: unknown) {
  const v = Math.round(Number(n))
  if (!Number.isFinite(v)) return 1
  return Math.min(5, Math.max(1, v))
}

export function isJudged(idea: Scored | null | undefined): idea is Scored & Record<Criterion, number> {
  return CRITERIA.every((c) => Number.isInteger(idea?.[c]))
}

export function scoreSum(idea: Scored | null | undefined): number | null {
  if (!isJudged(idea)) return null
  return idea.originality + idea.feasibility + idea.impact
}

export function medalFromSum(sum: number | null): number | null {
  if (sum == null) return null
  if (sum >= MEDAL_MIN_SUM[3]) return 3
  if (sum >= MEDAL_MIN_SUM[2]) return 2
  if (sum >= MEDAL_MIN_SUM[1]) return 1
  return 0
}

/** Medal shown for an idea: a hand-set medal wins over the judge. null = not judged yet. */
export function medal(idea: Scored | null | undefined): number | null {
  if (Number.isInteger(idea?.medalOverride)) return idea!.medalOverride!
  return medalFromSum(scoreSum(idea))
}

// A hand-set medal counts as a typical sum for that medal when breeding.
const OVERRIDE_FITNESS = [6, 10, 12, 14]

/** What evolution selects on. null = not part of the population yet. */
export function fitness(idea: Scored | null | undefined): number | null {
  if (Number.isInteger(idea?.medalOverride)) return OVERRIDE_FITNESS[idea!.medalOverride!]
  return scoreSum(idea)
}
