## 1. 配置

- [x] 1.1 在 `.github/dependabot.yml` 的五个更新项中各增加仅含 `patch` 的 `groups` 配置；以新增的 `dependabot-config.test.mjs` 通过为证
- [x] 1.2 新增 `self-analyst-app/src/test/js/dependabot-config.test.mjs`，断言每个更新项都有分组且 `update-types` 仅为 `patch`；在 `self-analyst-app` 下运行 `node --test` 通过

## 2. 规格与验证

- [x] 2.1 将增量同步到 `openspec/specs/dependency-auto-merge/spec.md`，运行 `openspec validate --all --strict` 通过
