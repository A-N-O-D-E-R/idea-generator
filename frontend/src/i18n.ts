// French and English. The interface language is per browser; generated text
// follows the language of its seed (the board's language is only a fallback).

import type { Lang } from './types'

const DICT: Record<Lang, Record<string, string>> = {
  fr: {
    appName: 'MUSE', appTagline: 'Multiple Unconventional Solutions Explorer',
    seedPlaceholder: 'Écris une idée ou colle un lien … (Entrée)',
    readingPage: 'Lecture de la page …',
    thinking: 'Réflexion …',
    boards: 'Tableaux',
    newBoard: 'Nouveau tableau',
    goal: 'Objectif',
    goalPlaceholder: 'ex. produit audiovisuel innovant pour l\'interaction dans les espaces publics',
    noGoal: 'Pas encore d\'objectif – clique pour fixer la direction',
    title: 'Nom',
    template: 'Modèle de flux',
    create: 'Créer',
    cancel: 'Annuler',
    save: 'Enregistrer',
    close: 'Fermer',
    delete: 'Supprimer',
    manual: 'Manuel',
    auto: 'Automatique',
    leads: 'Veille',
    dashboard: 'Tendances',
    exhibit: 'Exposition',
    export: 'Exporter',
    boardSettings: 'Réglages du tableau',
    appSettings: 'Réglages',
    fit: 'Tout afficher',
    // kinds
    kind_seed: 'Graine', kind_expand: 'Branche', kind_combine: 'Croisement', kind_guest: 'Invitée',
    generation: 'génération {n}',
    fromBoard: 'du tableau {b}',
    // detail
    notJudged: 'Pas encore évaluée',
    judging: 'Le jury lit …',
    originality: 'Originalité', feasibility: 'Faisabilité', impact: 'Impact',
    expand: 'Ramifier', expandHint: 'Trois nouvelles idées issues de celle-ci',
    combineWith: 'Croiser avec …', combinePick: 'Clique sur l\'idée à croiser (Échap annule). Tu peux aussi glisser une idée sur une autre.',
    rejudge: 'Réévaluer',
    medal: 'Médaille', medalAuto: 'Jury',
    announce: 'Annoncer', announceHint: 'Envoyer l\'idée comme invitée sur d\'autres tableaux, où elle peut se croiser avec leurs idées',
    toProductDesigner: 'Vers productDesigner', productDesignerHint: 'Envoyer l\'idée à productDesigner : un projet avec une carte pré-remplie (problème, questions, hors périmètre, brouillard)', productDesignerSent: 'productDesigner : projet « {name} » créé ({q} questions, {b} dans le brouillard).', productDesignerNote: 'productDesigner : les idées peuvent y être envoyées et deviennent un projet avec une carte pré-remplie. La clé (pd_…) se crée là-bas (POST /api/cles-api) et est stockée chiffrée sur le serveur.', toScientist: 'Vers AI Scientist', scientistHint: 'Envoyer l\'idée à ai-scientist comme job d\'idéation (idées de recherche avec vérification de nouveauté, 2 $ max)', scientistSent: 'Job AI Scientist {id} ({s}).', scientistUrl: 'Adresse', scientistNote: 'perso/ai-scientist : les idées peuvent y être envoyées comme jobs d\'idéation. La clé se crée là-bas (POST /v1/me/api-keys) et reste dans le navigateur.', spinoff: 'Nouveau tableau depuis l\'idée', spinoffHint: 'Affiner l\'idée sur son propre tableau, avec l\'idée comme objectif',
    project: 'Projet Claude Code', projectHint: 'Créer un dossier projet avec un CLAUDE.md dont Claude Code peut partir',
    projectMaking: 'Rédaction du brief …',
    revealProject: 'Afficher dans le Finder', copyStart: 'Copier la commande de démarrage', copied: 'Copié', downloadProject: 'Télécharger (.zip)',
    signInTitle: 'Connexion', signInNote: 'Chaque compte a ses propres tableaux, idées et clés API chiffrées.', signInWith: 'Se connecter avec {p}',
    signInFailed: 'La connexion a échoué. Réessaie.', signOut: 'Se déconnecter',
    source: 'Source', spinoffs: 'Tableaux dérivés', keywords: 'Mots-clés',
    deleteIdea: 'Supprimer l\'idée', confirmDelete: 'Supprimer « {t} » ?',
    // overview
    overview: 'Vue d\'ensemble', topIdeas: 'Meilleures idées', noIdeasYet: 'Le tableau est vide. Écris une graine ci-dessous, ou récupère des pistes depuis le panneau de gauche.',
    evolution: 'Évolution', evoLine: 'Génération {g} · moyenne {first} → {latest} · meilleure {best}', evoShort: 'Génération {g} · meilleure {best}',
    eloTitle: 'Classement Elo', duel: 'Duel', duelHint: 'Deux des meilleures idées s\'affrontent, deux fois en inversant l\'ordre.', duelWin: '{w} a battu {l}.', duelDraw: 'Égalité : {a} contre {b}.', eloLine: 'Elo {e} · {n} duels', summary: 'Synthèse', summarize: 'Synthétiser le tableau', resummarize: 'Mettre à jour', summaryStale: '{n} nouvelles idées depuis la synthèse',
    themes: 'Thèmes', strongest: 'Points forts', gaps: 'Lacunes', nextSteps: 'Prochaines étapes',
    // leads
    leadsX: 'Tendances X', leadsResearch: 'Recherche IA', refresh: 'Récupérer', refreshing: 'Veille en cours …',
    leadsEmpty: 'Rien de récupéré pour l\'instant. Les éclaireurs cherchent à partir de l\'objectif du tableau.', addAsSeed: '+ Graine', onBoard: 'sur le tableau',
    leadsDropped: '{n} pistes sans source vérifiée écartées', leadsNone: 'Aucune piste vérifiée trouvée cette fois',
    researcherOff: 'Cet éclaireur est désactivé dans les réglages du tableau.',
    dragLead: 'Glisse sur le tableau ou clique + Graine',
    // autopilot
    ap_manual: 'Manuel', ap_running: 'En cours …', ap_pace: 'Prochaine étape dans {s}', ap_hourly: 'Limite horaire atteinte · reprise {time}',
    ap_board_budget: 'Budget du jour épuisé · reprise demain', ap_global_budget: 'Budget global du jour épuisé · reprise demain',
    ap_backoff: 'Erreur · nouvel essai dans {s}', ap_error: 'Erreur : {m}', ap_idle: 'En attente', ap_idle_empty: 'En attente d\'une graine',
    ap_idle_nothing: 'Plus rien à faire pour l\'instant',
    apMeta: '{n} étapes dans la dernière heure · ${usd} aujourd\'hui',
    // board settings
    general: 'Général', language: 'Langue (par défaut pour le texte généré)', flow: 'Flux de création', flowName: 'Nom du flux',
    systemPrompt: 'Rôle (prompt système)', expandPrompt: 'Ramification', combinePrompt: 'Croisement', applyTemplate: 'Utiliser le modèle',
    saveAsTemplate: 'Enregistrer comme modèle', templateSaved: 'Modèle enregistré',
    jury: 'Jury', judgeGuidance: 'Consignes supplémentaires pour le jury', judgeGuidancePh: 'ex. Récompenser les solutions qui fonctionnent en extérieur toute l\'année.',
    autoProject: 'Le jury transforme les idées d\'or en projets Claude Code',
    diversity: 'Garder plusieurs familles d\'idées en vie (les niches encombrées se reproduisent moins)',
    researchers: 'Éclaireurs', xScout: 'Tendances X (Grok via OpenRouter)', researchScout: 'Recherche IA (Hugging Face)', focus: 'Focus supplémentaire',
    focusPh: 'en plus de l\'objectif, ex. « installations sonores »',
    autopilot: 'Pilote automatique', mode: 'Mode', pace: 'Secondes entre les étapes', stepsPerHour: 'Étapes max par heure', usdPerDay: 'USD max par jour',
    rateNote: 'Les limites en dollars ne comptent que les fournisseurs qui rapportent un coût (OpenRouter, Anthropic). 0 = modèles gratuits (locaux) uniquement.',
    models: 'Modèles', modelsBoardNote: 'Champ vide = réglage de l\'app.',
    role_distill: 'Distiller la graine', role_expand: 'Ramifier', role_combine: 'Croiser', role_judge: 'Jury', role_summary: 'Synthétiser',
    role_research: 'Éclaireur recherche', role_x: 'Éclaireur X', role_brief: 'Brief projet',
    deleteBoard: 'Supprimer le tableau', confirmDeleteBoard: 'Supprimer le tableau « {t} » et toutes ses idées ?',
    // app settings
    providers: 'Fournisseurs', apiKey: 'Clé API', baseUrl: 'Adresse', test: 'Tester', testOk: '{n} modèles', keyFrom_stored: 'clé enregistrée', keyStored: 'enregistrée · saisis pour remplacer', removeKey: 'Retirer', keyRemoved: 'retirée à l\'enregistrement',
    keyFrom_env: 'clé de l\'environnement', keyMissing: 'pas de clé', local: 'local',
    projectsRoot: 'Dossier des projets Claude Code', workspaceRoot: 'Espace d\'idées (facultatif)', workspaceNote: 'Dossier avec INDEX.md. Chaque projet y est aussi archivé sous ideas/<slug>/PITCH.md.', workspaceFiled: 'Archivé dans l\'espace : {p}', workspaceExisted: 'Déjà dans l\'espace : {p}', workspaceFailed: 'Impossible d\'archiver dans l\'espace : {e}', workspaceDuplicate: 'Non archivé : la même idée existe déjà dans l\'espace ({s}).', workspaceRelated: 'Lié à : {s}.', workspaceRepos: 'Réutilisable dans : {s}.', workspacePapers: '{n} travaux antérieurs dans PITCH.md.', reposRoots: 'Dossiers de code (facultatif)', reposNote: 'Dossiers dont les sous-dossiers sont des dépôts, un par ligne. Leurs README sont comparés à l\'idée avant archivage ; les correspondances vont dans EXISTING-ASSETS.md.', globalBudget: 'Limite globale, USD par jour', spend: 'Dépensé : ${today} aujourd\'hui · ${total} au total',
    settingsNote: 'Les réglages sont stockés sur le serveur pour ton compte. Les clés sont chiffrées et jamais renvoyées au navigateur.',
    settingsSaved: 'Réglages enregistrés',
    needKey: 'Ajoute une clé API pour {p} dans Réglages.',
    calibrateNote: 'Tu changes le modèle du jury ? Lance npm run calibrate -- --model <modèle> pour vérifier qu\'il distingue encore le faible du fort.',
    // announce
    announceTitle: 'Annoncer « {t} »', allBoards: 'Tous les tableaux', announced: 'Invitée sur {n} tableaux', announceNone: 'Déjà invitée là-bas',
    noOtherBoards: 'Il n\'y a pas encore d\'autres tableaux.',
    // dashboard
    dashTitle: 'Tendances sur tous les tableaux', days: '{n} jours', statIdeas: 'Idées', statBoards: 'Tableaux', statKeywords: 'Mots-clés',
    statRecent: 'Nouvelles idées, {n} jours', keyword: 'Mot-clé', trend: 'Par jour', count: 'Idées', change: 'Vs période précédente',
    onBoards: 'Tableaux', meanJury: 'Moyenne jury', boardKeywords: 'Mots-clés par tableau', ideasWith: 'Idées sur « {k} »',
    dashEmpty: 'Pas encore de mots-clés. Ils arrivent quand le jury évalue des idées.', tableNote: 'Survole une courbe pour la valeur du jour.',
    // report
    report: 'Rapport', reportNew: '{n} nouvelles', reportMedals: 'Médailles', reportProjects: 'Projets Claude Code',
    reportSpent: 'Dépensé, {n} jours', conclusions: 'Conclusions', writeConclusions: 'Rédiger les conclusions',
    rewriteConclusions: 'Réécrire', conclusionsHint: 'Laisser l\'IA lire tous les tableaux et rédiger les conclusions du rapport : enseignements, idées les plus prometteuses, opportunités et prochaines étapes.',
    conclusionsFrom: 'Rédigées le {date} · portaient sur {n} jours', insights: 'Enseignements', standouts: 'Les plus prometteuses', opportunities: 'Opportunités',
    noProjects: 'Pas encore de projets. Les idées d\'or deviennent des projets d\'elles-mêmes, ou clique → Projet Claude Code sur une idée.',
    ideasWord: 'idées', summaryOld: 'plus ancienne que le tableau', noBoardSummary: 'Pas encore de synthèse.',
    // misc
    gold: '🥇 Or : {t}', projectCreated: 'Projet Claude Code créé : {p}', exhibitExit: 'Échap quitte le mode exposition',
    err_no_key: 'Pas de clé API : {m}', err_bad_key: 'La clé a été refusée : {m}', err_unreachable: 'Service injoignable : {m}',
    err_no_search: '{m}', err_url_unreadable: 'Impossible de lire le lien : {m}', err_url_no_text: 'La page a trop peu de texte lisible.',
    err_too_few: 'La synthèse demande au moins deux idées.', err_generic: 'Une erreur est survenue : {m}',
    firstRun: 'Bienvenue ! Commence par un tableau et un objectif.',
  },
  en: {
    appName: 'MUSE', appTagline: 'Multiple Unconventional Solutions Explorer',
    seedPlaceholder: 'Write an idea or paste a link … (Enter)',
    readingPage: 'Reading the page …',
    thinking: 'Thinking …',
    boards: 'Boards',
    newBoard: 'New board',
    goal: 'Goal',
    goalPlaceholder: 'e.g. innovative audio-visual product for interaction in public spaces',
    noGoal: 'No goal yet – click to set the direction',
    title: 'Name',
    template: 'Flow template',
    create: 'Create',
    cancel: 'Cancel',
    save: 'Save',
    close: 'Close',
    delete: 'Delete',
    manual: 'Manual',
    auto: 'Automatic',
    leads: 'Scouting',
    dashboard: 'Trends',
    exhibit: 'Exhibition',
    export: 'Export',
    boardSettings: 'Board settings',
    appSettings: 'Settings',
    fit: 'Show all',
    kind_seed: 'Seed', kind_expand: 'Branch', kind_combine: 'Cross', kind_guest: 'Guest',
    generation: 'generation {n}',
    fromBoard: 'from board {b}',
    notJudged: 'Not judged yet',
    judging: 'The jury is reading …',
    originality: 'Originality', feasibility: 'Feasibility', impact: 'Impact',
    expand: 'Branch out', expandHint: 'Three new ideas growing out of this one',
    combineWith: 'Cross with …', combinePick: 'Click the idea to cross with (Esc cancels). You can also drag one idea onto another.',
    rejudge: 'Judge again',
    medal: 'Medal', medalAuto: 'Jury',
    announce: 'Announce', announceHint: 'Send the idea as a guest to other boards, where it can cross with their ideas',
    toProductDesigner: 'To productDesigner', productDesignerHint: 'Send the idea to productDesigner: a project with a seeded map (problem, questions, out of scope, fog)', productDesignerSent: 'productDesigner: project "{name}" created ({q} questions, {b} in the fog).', productDesignerNote: 'productDesigner: ideas can be sent there and become a project with a seeded map. Create the key (pd_…) there (POST /api/cles-api); it is stored encrypted on the server.', toScientist: 'To AI Scientist', scientistHint: 'Send the idea to ai-scientist as an ideation job (research ideas with novelty checks, at most $2)', scientistSent: 'AI Scientist job {id} ({s}).', scientistUrl: 'Address', scientistNote: 'perso/ai-scientist: ideas can be sent there as ideation jobs. Create the key there (POST /v1/me/api-keys); it stays in the browser.', spinoff: 'New board from idea', spinoffHint: 'Refine the idea on a board of its own, with the idea as the goal',
    project: 'Claude Code project', projectHint: 'Create a project folder with a CLAUDE.md that Claude Code can start from',
    projectMaking: 'Writing the brief …',
    revealProject: 'Show in Finder', copyStart: 'Copy start command', copied: 'Copied', downloadProject: 'Download (.zip)',
    signInTitle: 'Sign in', signInNote: 'Each account has its own boards, ideas and encrypted API keys.', signInWith: 'Sign in with {p}',
    signInFailed: 'Sign-in failed. Please try again.', signOut: 'Sign out',
    source: 'Source', spinoffs: 'Own boards', keywords: 'Keywords',
    deleteIdea: 'Delete idea', confirmDelete: 'Delete "{t}"?',
    overview: 'Overview', topIdeas: 'Top ideas', noIdeasYet: 'The board is empty. Write a seed below, or pull leads from the left panel.',
    evolution: 'Evolution', evoLine: 'Generation {g} · mean {first} → {latest} · best {best}', evoShort: 'Generation {g} · best {best}',
    eloTitle: 'Elo ranking', duel: 'Duel', duelHint: 'Two of the best ideas are compared head to head, twice with the order swapped.', duelWin: '{w} beat {l}.', duelDraw: 'Draw: {a} vs {b}.', eloLine: 'Elo {e} · {n} duels', summary: 'Summary', summarize: 'Summarize the board', resummarize: 'Refresh', summaryStale: '{n} new ideas since the summary',
    themes: 'Themes', strongest: 'Strongest', gaps: 'Gaps', nextSteps: 'Next steps',
    leadsX: 'X trends', leadsResearch: 'AI research', refresh: 'Fetch', refreshing: 'Scouting …',
    leadsEmpty: 'Nothing fetched yet. The scouts search from the board\'s goal.', addAsSeed: '+ Seed', onBoard: 'on the board',
    leadsDropped: '{n} leads without a verified source were dropped', leadsNone: 'No verified leads found this time',
    researcherOff: 'This scout is switched off in the board settings.',
    dragLead: 'Drag onto the board or click + Seed',
    ap_manual: 'Manual', ap_running: 'Working …', ap_pace: 'Next step in {s}', ap_hourly: 'Hourly limit reached · resumes {time}',
    ap_board_budget: 'Daily budget spent · resumes tomorrow', ap_global_budget: 'Global daily budget spent · resumes tomorrow',
    ap_backoff: 'Error · retrying in {s}', ap_error: 'Error: {m}', ap_idle: 'Waiting', ap_idle_empty: 'Waiting for a seed',
    ap_idle_nothing: 'Nothing more to do right now',
    apMeta: '{n} steps in the last hour · ${usd} today',
    general: 'General', language: 'Language (fallback for generated text)', flow: 'Creation flow', flowName: 'Flow name',
    systemPrompt: 'Role (system prompt)', expandPrompt: 'Branching', combinePrompt: 'Crossing', applyTemplate: 'Use template',
    saveAsTemplate: 'Save as template', templateSaved: 'Template saved',
    jury: 'Jury', judgeGuidance: 'Extra guidance for the jury', judgeGuidancePh: 'e.g. Reward solutions that work outdoors all year.',
    autoProject: 'The jury turns gold ideas into Claude Code projects',
    diversity: 'Keep several idea families alive (crowded niches breed less)',
    researchers: 'Scouts', xScout: 'X trends (Grok via OpenRouter)', researchScout: 'AI research (Hugging Face)', focus: 'Extra focus',
    focusPh: 'besides the goal, e.g. "sound installations"',
    autopilot: 'Autopilot', mode: 'Mode', pace: 'Seconds between steps', stepsPerHour: 'Max steps per hour', usdPerDay: 'Max USD per day',
    rateNote: 'Dollar limits only count providers that report cost (OpenRouter, Anthropic). 0 = free (local) models only.',
    models: 'Models', modelsBoardNote: 'Empty field = the app setting.',
    role_distill: 'Distill seed', role_expand: 'Branch', role_combine: 'Cross', role_judge: 'Jury', role_summary: 'Summarize',
    role_research: 'Research scout', role_x: 'X scout', role_brief: 'Project brief',
    deleteBoard: 'Delete board', confirmDeleteBoard: 'Delete the board "{t}" with all its ideas?',
    providers: 'Providers', apiKey: 'API key', baseUrl: 'Address', test: 'Test', testOk: '{n} models', keyFrom_stored: 'key saved', keyStored: 'saved · type to replace', removeKey: 'Remove', keyRemoved: 'removed when you save',
    keyFrom_env: 'key from environment', keyMissing: 'no key', local: 'local',
    projectsRoot: 'Folder for Claude Code projects', workspaceRoot: 'Idea workspace (optional)', workspaceNote: 'Folder with INDEX.md. Every project is also filed there as ideas/<slug>/PITCH.md.', workspaceFiled: 'Filed in the workspace: {p}', workspaceExisted: 'Already in the workspace: {p}', workspaceFailed: 'Could not file into the workspace: {e}', workspaceDuplicate: 'Not filed: the same idea is already in the workspace ({s}).', workspaceRelated: 'Linked to: {s}.', workspaceRepos: 'Reusable in: {s}.', workspacePapers: '{n} prior works in PITCH.md.', reposRoots: 'Code folders (optional)', reposNote: 'Folders whose sub-folders are repos, one per line. Their READMEs are compared with the idea before filing; hits go to EXISTING-ASSETS.md.', globalBudget: 'Global limit, USD per day', spend: 'Spent: ${today} today · ${total} in total',
    settingsNote: 'Settings are stored on the server for your account. Keys are stored encrypted and never sent back to the browser.',
    settingsSaved: 'Settings saved',
    needKey: 'Add an API key for {p} under Settings.',
    calibrateNote: 'Changing the jury model? Run npm run calibrate -- --model <model> to check it still tells weak from strong.',
    announceTitle: 'Announce "{t}"', allBoards: 'All boards', announced: 'Guest on {n} boards', announceNone: 'Already a guest there',
    noOtherBoards: 'There are no other boards yet.',
    dashTitle: 'Trends across all boards', days: '{n} days', statIdeas: 'Ideas', statBoards: 'Boards', statKeywords: 'Keywords',
    statRecent: 'New ideas, {n} days', keyword: 'Keyword', trend: 'Per day', count: 'Ideas', change: 'Vs previous period',
    onBoards: 'Boards', meanJury: 'Mean jury', boardKeywords: 'Keywords per board', ideasWith: 'Ideas about "{k}"',
    dashEmpty: 'No keywords yet. They arrive as the jury judges ideas.', tableNote: 'Hover a line for the value of the day.',
    report: 'Report', reportNew: '{n} new', reportMedals: 'Medals', reportProjects: 'Claude Code projects',
    reportSpent: 'Spent, {n} days', conclusions: 'Conclusions', writeConclusions: 'Write conclusions',
    rewriteConclusions: 'Rewrite', conclusionsHint: 'Let the AI read every board and write the conclusions: insights, the ideas most worth pursuing, opportunities and next steps.',
    conclusionsFrom: 'Written {date} · covered {n} days', insights: 'Insights', standouts: 'Most worth pursuing', opportunities: 'Opportunities',
    noProjects: 'No projects yet. Gold ideas become projects by themselves, or click → Claude Code project on an idea.',
    ideasWord: 'ideas', summaryOld: 'older than the board', noBoardSummary: 'No summary yet.',
    gold: '🥇 Gold: {t}', projectCreated: 'Claude Code project created: {p}', exhibitExit: 'Esc leaves exhibition mode',
    err_no_key: 'No API key: {m}', err_bad_key: 'The key was not accepted: {m}', err_unreachable: 'Cannot reach the service: {m}',
    err_no_search: '{m}', err_url_unreadable: 'Could not read the link: {m}', err_url_no_text: 'The page has too little readable text.',
    err_too_few: 'The summary needs at least two ideas.', err_generic: 'Something went wrong: {m}',
    firstRun: 'Welcome! Start with a board and a goal.',
  },
}
DICT.fr.updateTemplate = 'Mettre à jour le modèle'
DICT.fr.deleteTemplate = 'Supprimer le modèle'
DICT.fr.templateDeleted = 'Modèle supprimé'
DICT.fr.confirmDeleteTemplate = 'Supprimer le modèle « {t} » ?'
DICT.en.updateTemplate = 'Update template'
DICT.en.deleteTemplate = 'Delete template'
DICT.en.templateDeleted = 'Template deleted'
DICT.en.confirmDeleteTemplate = 'Delete the template "{t}"?'

const stored = localStorage.getItem('ideagen.lang')
let lang: Lang = stored === 'fr' || stored === 'en' ? stored : navigator.language?.startsWith('fr') ? 'fr' : 'en'
document.documentElement.lang = lang

export const getLang = () => lang
export function setLang(l: string) {
  lang = l === 'en' ? 'en' : 'fr'
  localStorage.setItem('ideagen.lang', lang)
  document.documentElement.lang = lang
}

export function t(key: string, vars: Record<string, string | number | null | undefined> = {}) {
  let s = DICT[lang][key] ?? DICT.fr[key] ?? key
  for (const [k, v] of Object.entries(vars)) s = s.replaceAll(`{${k}}`, String(v))
  return s
}

/** A friendly message for an API error code. */
export function errorText(e: unknown) {
  const err = e as { code?: string; message?: string } | null
  const key = `err_${err?.code}`
  return DICT[lang][key] ? t(key, { m: err?.message }) : t('err_generic', { m: err?.message ?? String(e) })
}

export const locale = () => (lang === 'fr' ? 'fr-FR' : 'en-GB')
