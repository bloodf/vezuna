# Accessibility spike: four items the static audit could not prove

Date: 2 November 2026. Scope: Compose Multiplatform 1.12.2 on iOS, plus the Vezuna lock path. Method: I read the symbols and IR string tables of the shipped `ui-iosSimulatorArm64Main-1.12.2.klib` (the CMP iOS accessibility bridge) and the Vezuna sources. **I did not run VoiceOver.** Every result below comes from the code, so each still needs a manual VoiceOver check on a device or simulator before release. That check is listed under "Still to verify by hand".

## 1. Do liveRegion, heading and stateDescription reach VoiceOver?

| Compose semantics | What the CMP iOS bridge does (klib evidence) | Status |
|---|---|---|
| `heading()` | Maps `SemanticsProperties.Heading` to `UIAccessibilityTraitHeader`. The same lambda maps `Selected` to `UIAccessibilityTraitSelected`. | Mapped. The VoiceOver rotor "Headings" should list RootHeader titles, section headings, sheet titles and EmptyState titles. |
| `liveRegion` | Sets `UIAccessibilityTraitUpdatesFrequently`. While it traverses nodes, `isLiveRegion` compares the old and new label and value and posts `UIAccessibilityAnnouncementNotification` with the new text. `UIAccessibilityLayoutChangedNotification` and `UIAccessibilityScreenChangedNotification` are also posted. | Mapped. The announcement fires **only when an existing live node's label changes**. A live node that is newly added is not announced. |
| `stateDescription` | Read together with `ToggleableState`, `Selected` and `ProgressBarRangeInfo` when the node's value is built (the iOS `accessibilityValue`). | Mapped as the element's value. |
| `error()` | Not found as a separate trait. | It shows up only as the field's supporting text, which belongs to the same node. |

What I changed because of this: `FeedbackHost` now keeps its live-region node composed even when there is no message. A new notice or error therefore changes that node's label, and iOS announces it. Every other R0 component relies only on heading, selected and value, and those are mapped.

## 2. Dynamic Type at the largest sizes

The bridge has a `uiContentSizeCategoryToFontScaleMap`. It reads `preferredContentSizeCategory` from the trait collection, and the map includes the accessibility categories up to `UIContentSizeCategoryAccessibilityExtraExtraExtraLarge`. Compose `sp` text therefore follows Dynamic Type through `LocalDensity.fontScale`, as Android font scale does.

The R0 components keep text readable when that scale grows:
- No component sets a fixed height around text.
- Navigation labels wrap to two lines.
- The profile name has the whole header row.
- `FieldPair` stacks from font scale 1.3 or when the width is under 360dp.

The screenshot run on simulator F39F1F9A, with a large text size set, showed two truncations. Both are fixed in R0: the profile name became "A…", and "Records" became "Record".

Still to verify by hand: every lane screen at AX5 in PT and ES.

## 3. Does focus return after a dialog on iOS?

The bridge reads `IsDialog` and `IsPopup` when it merges semantics. I found no `accessibilityViewIsModal` symbol and no code that restores focus. Whether VoiceOver focus goes back to the button that opened the dialog cannot be shown from the binary.

**Result: unproven.** Lanes that add dialogs or sheets should follow the audit checklist item 11. That means storing a `FocusRequester` at the call site and requesting it when the dialog dismisses. Then check by hand with VoiceOver: open and close `ConfirmDialog` and the profile switcher sheet, and confirm focus lands back on the button that opened them.

## 4. Does the lock cover remove health nodes from the accessibility tree?

- **Compose side.** `AppLockHost` (`shared/.../ui/AppLockUi.kt`) composes `LockScreen` *instead of* `content()` while the app is locked. `VezunaApp` and every route sit inside `content`. So while locked, no health node exists in the semantics tree on Android or iOS. VoiceOver and TalkBack cannot reach them because they are not composed at all.
- **Native sheets.** `ContentView.swift` sets `model.sheet = nil` without animation when `lock.locked` becomes true. Native import, export, HealthKit, license, notices and privacy sheets close, so their content leaves the tree.
- **App switcher cover.** The cover in `AppLockSupport.swift` is `accessibilityHidden(true)` and shows only a lock symbol. It covers the snapshot, not the live tree, which is already empty because of the Compose side.

**Result: proven by code.** Still to verify by hand: lock with VoiceOver on, swipe through every element, and confirm only the lock screen and its Unlock button are reachable.

## Still to verify by hand (needs a VoiceOver or TalkBack session)

1. The Headings rotor lists root titles, section headings and sheet titles.
2. Saving a result announces "Result saved" once, without moving focus. A blocking error is announced assertively.
3. Focus returns to the button that opened `ConfirmDialog` and `ProfileSwitcherSheet`.
4. At AX5, in PT and ES, the root screens, the More rows and the tab labels do not clip.
5. While locked, only the lock screen is reachable.
