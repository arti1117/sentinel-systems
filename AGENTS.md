# Repository Guidelines

## Scope Boundary (must read first)
This repository is a **PUBLIC flagship** — the operator / observability plane of a two-persona engineering portfolio (its engineer counterpart is the `fleet-master-controller` repo). It is meant to be surfaced: linked from the blog's Projects tab, the portfolio, and the résumé.

- **Represent the stage honestly.** Current stage = market research + system analysis + business-framing docs (no production code yet). Do not describe planned work as built; clearly separate design/research from implemented.
- **No confidential or third-party material.** Nothing sourced from an employer (GME) — secrets, internal systems, incident specifics, customer PII — and no third-party NDA/contract detail. Use generic, domain-level language for anything informed by work experience.
- If this file conflicts with `/home/jy/Documents/AGENTS.md`, the stricter confidentiality boundary still wins.

## Project Structure & Module Organization
`README.md` explains the product vision. All contributor-facing content lives under `docs/`.
- `docs/strategy/`: business principles, decision templates, and project framing
- `docs/research/`: market, customer, and willingness-to-pay research
- `docs/technical/`: architecture notes and stack deep dives
- `docs/guides/`: onboarding and curriculum-style learning materials
- `docs/INDEX.md`: master table of contents; update it whenever you add, rename, or remove a document

## Build, Test, and Development Commands
This is a documentation-first repository, so there is no application build or automated test runner today. Use lightweight local checks before opening a PR:

```bash
find docs -type f | sort   # confirm file placement and naming
git diff --check           # catch trailing whitespace and merge markers
rg -n '\]\(' docs          # review Markdown links after edits
```

Preview Markdown in your editor before submitting, especially tables, callouts, and diagrams.

## Coding Style & Naming Conventions
Write prose in clear Korean unless a template or identifier is already English. Introduce technical terms with English in parentheses when useful, for example `관측성(Observability)`. Use ATX headings (`#`, `##`), short paragraphs, and fenced code blocks with language tags. Prefer ASCII diagrams over Mermaid for portability; recent commits explicitly moved in that direction. Match existing filename patterns: descriptive Korean titles for guides (`자율주행-401-시니어-아키텍트.md`) and concise English names for reusable templates (`Project Template.md`).

## Testing Guidelines
Validation here is editorial rather than executable. Before merging:
- verify internal links and section anchors
- confirm new pages are indexed from `docs/INDEX.md`
- re-read rendered tables, lists, and code fences for formatting issues
- fact-check technical or market claims against the source material you used

## Commit & Pull Request Guidelines
Recent history uses short, imperative commit subjects with a scope prefix, such as `docs: replace mermaid diagrams with native ascii graphics` or `docs/technical: replace mermaid with ascii art for broader compatibility`. Keep each commit focused on one document set or one editorial intent.

PRs should include a brief summary, the affected paths, and any navigation updates such as `docs/INDEX.md`. Add screenshots only when formatting or layout changed in a way that is easier to review visually.
