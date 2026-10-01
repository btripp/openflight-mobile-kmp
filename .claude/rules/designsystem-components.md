---
paths:
  - "core/designsystem/**/*.kt"
---

# Writing `core:designsystem` components (`Of*`)

From the androidx [Compose component API guidelines](https://github.com/androidx/androidx/blob/androidx-main/compose/docs/compose-component-api-guidelines.md)
and [Compose API guidelines](https://github.com/androidx/androidx/blob/androidx-main/compose/docs/compose-api-guidelines.md).
compose-rules (run by `spotlessCheck`) enforces several of these mechanically.

- **Parameter order:** required parameters, then `modifier: Modifier = Modifier` as the first
  optional parameter, then other optional parameters, then a trailing `content` slot. Apply
  `modifier` once, to the root element only.
- **Emit or return, not both.** A `@Composable` that emits UI returns `Unit`; one that returns a
  value (`rememberOfWindowClass()`) emits nothing and is named `remember…`.
- **Stateless and hoisted.** Take `value` + `onValueChange` (or `selected` + `onClick`), not
  internal state that callers can't control. Defaults (colors, sizes, paddings) are parameter
  defaults; move them to an `Of<Name>Defaults` object once more than one component shares them.
- **Slots:** `content: @Composable () -> Unit` (with a scope receiver when the layout offers one).
  Never invoke the same slot in two branches of an `if`/`when`: its state is lost when the branch
  flips (e.g. `OfWindowClass` changing on rotation or resize). Wrap it in
  `remember(content) { movableContentOf(content) }` and call that instead.
- Wrap Material3 here and only here (invariant 5). Every new component gets a phone `@Preview`,
  and a tablet one when its layout adapts.
