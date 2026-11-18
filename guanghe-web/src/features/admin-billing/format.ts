/**
 * The back office's view of the shared display helpers.
 *
 * These are the same functions the user-facing commercial surfaces format with: a second copy of a
 * money formatter is a second chance to disagree about a cent, so the back office re-exports the one
 * implementation in `src/shared/format.ts` instead of carrying its own. `AdminErrorView` is kept as
 * the name this feature's components already use for the shared `ApiErrorView`.
 */

export * from '../../shared/format'
export type { ApiErrorView as AdminErrorView } from '../../shared/format'
