# LocalRepoServer

Spring Boot (Java, Maven) local repository server for Gradle projects. See `README.md`.

<!-- code-review-graph MCP tools -->
## MCP Tools: code-review-graph

This project has a single-repo code-review-graph index at the project root
(16 files, ~125 nodes, ~585 edges as of 2026-10-07). The repo is small, so the
graph is a convenience, not a necessity — use it deliberately.

### Reach for the graph FIRST when

- **Finding entities by concept**: `semantic_search_nodes`
- **Module / file structure**: `query_graph` patterns `file_summary`, `children_of`, `imports_of`, `importers_of`
- **Inheritance + tests**: `query_graph` patterns `inheritors_of`, `tests_for`
- **High-level structure**: `get_architecture_overview`

### Skip the graph and use Grep/Read when

- **Tracing calls through Spring-injected beans** — CALLS edges drop when the
  receiver type comes from constructor/field injection. Treat 0-result
  `callers_of` / `callees_of` as "indexer couldn't resolve," not "no callers."
- **Impact analysis** (`get_impact_radius`, `detect_changes`) — built on CALLS,
  under-reports. Use for hints only.
- **Config / `application.properties` / `pom.xml`** — not graph-indexed.

### Workflow

1. Graph auto-updates on Edit/Write via the `PostToolUse` hook in `.claude/settings.json`.
2. To rebuild after major refactors: `build_or_update_graph_tool` with
   `full_rebuild=true` (or `code-review-graph build --repo <root>`).
