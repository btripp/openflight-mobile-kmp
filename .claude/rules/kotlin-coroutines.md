---
paths:
  - "**/src/commonMain/**/*.kt"
  - "**/src/androidMain/**/*.kt"
  - "**/src/iosMain/**/*.kt"
  - "**/src/main/**/*.kt"
---

# Coroutines and Flow (all production Kotlin)

detekt's coroutine rules (`InjectDispatcher`, `SuspendFunSwallowedCancellation`,
`SuspendFunWithFlowReturnType`, …) need type resolution, which never runs on `commonMain` in
detekt 1.23.8. Only `GlobalCoroutineUsage` is on. So this file is the guard: follow it by hand.
Background: Android's [coroutines best practices](https://developer.android.com/kotlin/coroutines/coroutines-best-practices).

- **Inject dispatchers.** A class that switches dispatchers takes one as a constructor parameter
  with a default (`computeDispatcher: CoroutineDispatcher = Dispatchers.Default`, as in
  `BagViewModel`), so tests pass their test dispatcher. Don't hard-code `Dispatchers.*` inside a
  function body. Long-lived scopes are built in Koin modules (`DataModule`), not in the class.
- **Main-safe suspend functions.** A `suspend` function never blocks its caller's thread; move
  blocking or CPU-heavy work with `withContext(injectedDispatcher)` inside the function that does
  it, not at the call site.
- **Who launches.** ViewModels launch in `viewModelScope`. Repositories and data sources expose
  `suspend` functions and `Flow`, and launch only in a scope they're given (for work that must
  outlive the caller). No `GlobalScope` (detekt enforces this), no `CoroutineScope(...)` created
  ad hoc in a function.
- **A suspend function returns a value; a `Flow` is returned by a plain function.** No
  `suspend fun x(): Flow<T>`.
- **Never swallow cancellation.** A catch-all in suspend code, or in code a coroutine runs, puts the
  repo's idiom first:
  ```kotlin
  } catch (cancellation: CancellationException) {
      throw cancellation
  } catch (failure: Exception) {
  ```
  Don't use `runCatching` around suspend calls: it catches `CancellationException`, so the
  coroutine keeps running after its scope was cancelled.
- **State.** A private `MutableStateFlow` named `mutableX`, exposed as `val x: StateFlow<T> =
  mutableX.asStateFlow()` (the repo's naming, ~80 sites; don't introduce `_x`). Never expose the
  mutable type. Derived state uses `stateIn(scope, SharingStarted.WhileSubscribed(...), initial)`
  or `combine`.
- **One-shot effects** go through a `Channel` read as `effects: Flow` (`receiveAsFlow()`). This is
  a deliberate exception to Android's "don't send events from the ViewModel to the UI" guidance,
  and ADR 0001 and `kmp-shared-code.md` rely on it. Keep it; don't "fix" it into state.
