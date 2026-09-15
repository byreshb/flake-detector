# Quarantine

How to take a known-flaky test out of the way of the build without losing track of it.

## The ledger

Quarantine decisions live in `.flake/quarantine.yaml`, checked into the repository like any other
file, so who quarantined what, and why, is in `git blame` and code review, not a chat thread.

```yaml
# Quarantined tests. Every entry needs an owner, a reason and an expiry at most 90 days
# after it was added; expired entries fail the build. See docs/quarantine.md.
entries:
  - test: com.acme.CheckoutTest#appliesCoupon
    reason: timing on CI
    owner: byresh
    added: 2026-09-14
    expires: 2026-12-01
    issue: https://github.com/acme/shop/issues/42
```

Every entry needs:

| Field     | Required | Meaning                                                                 |
|-----------|----------|--------------------------------------------------------------------------|
| `test`    | yes      | `className#methodName`                                                   |
| `reason`  | yes      | Why it is quarantined, for the next person who reads the ledger          |
| `owner`   | yes      | Who is responsible for fixing it or removing the entry                   |
| `added`   | yes      | The day it was quarantined                                               |
| `expires` | yes      | The day the quarantine stops protecting the test; **at most 90 days after `added`** |
| `issue`   | no       | A tracking issue or ticket                                               |

The 90-day cap is enforced when an entry is constructed, whether through `flake quarantine add`
or by hand-editing the file and running any command that loads it: `QuarantineEntry` refuses to be
built otherwise. There is no way to quarantine a test indefinitely. A test that is still flaky
after 90 days needs a fresh, deliberate decision to extend it, not silence.

## Why an expiry, not a permanent flag

Every disabled-test mechanism eventually accumulates tests nobody remembers disabling. An expiry
forces the choice back into view: either the test gets fixed, or someone re-reads the reason and
decides it is still worth quarantining, which is a much smaller ask than "go find every
`@Disabled` in the codebase and figure out which ones still apply."

## Commands

```bash
flake quarantine add com.acme.CheckoutTest#appliesCoupon \
  --reason "timing on CI" --owner byresh --expires 2026-12-01 [--issue URL] [--added DATE]
flake quarantine remove com.acme.CheckoutTest#appliesCoupon
flake quarantine list [--expired-only]
flake quarantine check
```

`add` replaces any existing entry for the same test. `--added` defaults to today. `list` prints a
Markdown table with days left or "expired Nd ago" for each entry. `check` exits non-zero and lists
every expired entry on stderr; run it as its own CI step (see
[docs/ci-integration.md](ci-integration.md)) so an expired quarantine is caught even when nothing
in the test suite happens to fail that day.

## How quarantine changes what `flake gate` does

`flake gate` reads the reports of the current build and fails only for a failing test that is
**not** quarantined (or whose quarantine has expired) and whose flakiness score is at or below the
threshold. A quarantined, unexpired failure is printed and skipped:

```
SKIP  com.acme.CheckoutTest#appliesCoupon: quarantined by byresh until 2026-12-01 (timing on CI)
```

An expired one is not excused; it is printed and treated as a normal failure, with a message that
says so directly, so the exit code alone never hides the reason:

```
FAIL  com.acme.CheckoutTest#appliesCoupon: quarantine expired on 2026-08-01; treated as a normal failure
```

Quarantine and the flakiness threshold are two independent excuses: a test can be excused by
either without the other. See [docs/scoring.md](scoring.md) for the threshold and
[docs/ci-integration.md](ci-integration.md) for wiring `gate` and `check` into a workflow.

## Removing an entry

Delete it with `flake quarantine remove`, or edit the YAML directly, once the test is fixed or the
team decides it is not worth the effort and should be deleted instead. There is no separate
"resolved" state: an entry either protects a test or it does not exist.
