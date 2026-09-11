# TaskStore Composition: Is the Spring AI Advisor Mechanism Applicable?

> 🇨🇳 [简体中文](ANALYSIS-TaskStore-Composition.zh-CN.md)

> Status: **research complete, decision is to shelve (deferred), not implemented.** This document is a
> record only, for future re-evaluation.
> Trigger question: can the Spring AI Advisor layer mechanism be borrowed to merge
> `upload-file-store-redis` and `upload-file-store-jdbc`?

## 1. Motivation

Today `-store-redis` and `-store-jdbc` are two parallel, mutually exclusive, single-choice optional
modules; only one can be active at runtime, and they cannot be stacked (e.g. "Redis as a read cache +
JDBC as the persistence layer"). The question was whether an interceptor-chain mechanism like the
Spring AI Advisor could unify/compose the two storage backends.

## 2. Spring AI Advisor in a nutshell

An Advisor is an **ordered around-advice chain** on a `ChatClient` call:

- Core interfaces: `CallAdvisor` / `CallAdvisorChain` (sync), `StreamAdvisor` / `StreamAdvisorChain`
  (streaming); key methods `adviseCall(req, chain)` / `adviseStream(...)`.
- Each Advisor calls `chain.nextCall(req)` to hand the request to the next link; it may modify the
  request **before** `next` and the response **after**, or **skip `next` to short-circuit** and fill in
  the response itself.
- `getOrder()` ordering: a lower value processes the request first and the response last (stack-like,
  i.e. AOP around advice).
- `ChatClientRequest` / `ChatClientResponse` carry a shared `advise-context` for cross-Advisor state.
- The framework automatically appends a terminal link that actually sends the request to the model.

In one sentence: **Advisor = chain of responsibility + decorator + shared context**, used to
intercept/enhance a call, not to package or merge modules.

## 3. Has it been applied to this component?

**No.** There is no `Interceptor / Filter / Chain / Composite / Advisor / Decorator` implementation
anywhere in production code. The existing extension points are all "single-implementation replacement"
or "observer", not chain wrapping:

| Extension point | Location | Shape |
| --- | --- | --- |
| `TaskStore` | `upload-file-core/.../core/store/TaskStore.java:21` | SPI (`get/save/remove/list`) |
| `ChunkStorage` | `.../core/storage/ChunkStorage.java:20` | SPI |
| `CleanupLock` | `.../core/util/CleanupLock.java:17` | SPI |
| `AccessControl` | `.../core/security/AccessControl.java:34` | SPI (decision) |
| `AccessControlListener` | `.../core/security/AccessControlListener.java:18` | broadcast listener |
| `UploadErrorRenderer` | `.../core/error/UploadErrorRenderer.java:18` | SPI (render) |
| Auto-configured beans | starter `UploadFileAutoConfiguration` | `@ConditionalOnMissingBean` override |

`AccessControlListener` is the closest thing to a "chain", but it is a **broadcast observer**
(`CopyOnWriteArrayList`; it only notifies, never wraps or short-circuits), which differs from the
Advisor around-chain semantics.

## 4. Current state of `-redis` / `-jdbc`

The two are parallel, mutually exclusive, single-choice; one is selected at startup by configuration:

- `RedisTaskStore`: `upload-file-store-redis/.../RedisTaskStore.java:31`
- `JdbcTaskStore`: `upload-file-store-jdbc/.../JdbcTaskStore.java:32`
- Selection logic: `metadata-store=memory|file|jdbc|redis|auto`, see
  `UploadFileAutoConfiguration.uploadFileTaskStore()` (javax `:109-153`; jakarta is structurally
  identical).
- `jdbc/redis` are detected by **class-name reflection** (`JDBC_STORE_CLASS` / `REDIS_STORE_CLASS`);
  when the module or the `DataSource` is absent it logs a warning and falls back to `auto`
  (`metadata-dir` present → `FileTaskStore`, otherwise `MemoryTaskStore`).
- Both store modules are declared `<optional>true</optional>` in the starter and are **deliberately not
  pulled in transitively**, so Jedis / a JDBC driver is never forced on every consumer.

**Conclusion: stacking is currently impossible; only one `TaskStore` bean is wired.**

## 5. Three readings of "merge" and their assessment

| Reading | Advisor idea applicable? | Assessment |
| --- | --- | --- |
| A. Package into a single artifact | No | A build/dependency concern unrelated to Advisor; merging would drag Jedis + JDBC into every consumer, breaking the deliberate optional design. **Not recommended.** |
| B. Runtime composition (Redis L1 cache + JDBC L2 persistence / dual write) | Yes (chain-of-responsibility analogy) | The only scenario that genuinely matches "chain wrapping". Could add a `CompositeTaskStore` (decorator chain ending at the real backend). **Valuable, but the semantics must be pinned down first.** |
| C. Unify the abstraction/naming | No | The interface is already `TaskStore`; merely consolidating module names yields little. |

## 6. If option B is implemented later: design points and semantic pitfalls

The Advisor analogy only provides the shape of a "chain"; it does not solve storage semantics. Decide
these first:

- `list()`: in a cache + DB chain, is it "merge and dedupe" or "authoritative source (DB) only"?
- `save()`: write-through (synchronous dual write) or write-behind (async flush)? How are failures
  compensated?
- `remove()`: deletion order, cache invalidation vs. DB deletion consistency.
- Whether a `get()` miss back-fills the cache, the TTL policy, and interaction with `TaskStoreMigrator`
  / `UploadTask.schemaVersion`.
- Concurrency and cache coherence across instances (reuse the existing `IdentifierLock` /
  `CleanupLock`).
- The chain must end at an authoritative store; decorator links must not change the failure semantics
  of `get/save/remove/list` (`TaskStore` has no explicit exception contract — standardize on runtime
  exceptions and document them).

## 7. Decision and re-evaluation triggers

- **Decision**: shelved, not implemented; keep the current parallel optional `-redis` / `-jdbc` modules.
- **Re-evaluation triggers** (any one):
  1. A clear multi-tier "cache + persistence" requirement emerges (performance or cost driven);
  2. Integrators ask to use Redis and a relational database together in one deployment;
  3. A decision is made to converge the store modules into a composable plug-in system (re-assess
     naming and coordinates then).

## 8. References

- Spring AI Advisors API: <https://docs.spring.io/spring-ai/reference/api/advisors.html>
- Related design docs: [DESIGN.md](DESIGN.md) (module dependencies and component responsibilities)
