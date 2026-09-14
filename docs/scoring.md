# Scoring

How a test's run history becomes a number between 0 and 1, and why. Every formula here is pinned
by the fixtures in [`conformance/scoring.json`](../conformance/scoring.json); the Java scorer in
`flake-core` and the TypeScript scorer in `action/` are both tested against the same file, so the
two implementations cannot drift apart silently.

## Inputs

The scorer sees every run of one test: the commit, the build id and attempt, the rerun index, the
outcome, the hash of the failure message, the runner label and the build timestamp. Skipped runs
are dropped. The rest are sorted chronologically (build timestamp, then attempt, then rerun index)
and grouped by commit.

Everything that compares two runs compares runs **on the same commit**. A test that failed on
commit A and passed on commit B may simply have been fixed; a test that failed and passed on the
same commit had nothing to do with the code.

## Components

### 1. Rerun-recovery rate (weight 0.5)

The strongest signal. A failure is *recovered* when a later run on the same commit passed, whether
that later run was a Surefire rerun in the same build, or a re-run of the whole workflow.

```
rerunRecoveryRate = recoveredFailures / failures        (0 when failures = 0)
```

### 2. Flip rate (weight 0.3)

Within each commit group, every consecutive pair of runs is a trial; a trial is a *flip* when one
run failed and the other did not (FAIL and ERROR both count as failed, so FAIL followed by ERROR is
not a flip).

```
flipRate = flips / flipPairs                             (0 when flipPairs = 0)
```

### 3. Failure-message entropy (weight 0.2)

Many distinct failure messages point at the environment (timeouts, connection resets, races that
fail in different places); one message repeated points at a bug. The failure messages are hashed
after normalisation (see the README) and the Shannon entropy of the hash distribution over the
failures is scaled to [0, 1]:

```
H        = - sum over hashes of  p(hash) * log2 p(hash)
entropy  = H / log2(failures)                            (0 when failures < 2)
```

Two failures with two messages already give entropy 1, which is not much evidence, so the value
fed into the score is shrunk by the number of failures:

```
entropyComponent = entropy * (1 - 1 / failures)          (0 when failures = 0)
```

### Confidence: the Wilson interval

A rate of 1 from a single pair should not outrank a rate of 0.5 from three hundred pairs. Both
rates above therefore enter the score as the **lower bound of their 95% Wilson score interval**,
not as the raw rate:

```
p      = successes / n,  z = 1.96
centre = p + z^2 / (2n)
half   = z * sqrt( p(1 - p) / n + z^2 / (4n^2) )
lower  = (centre - half) / (1 + z^2 / n)
upper  = (centre + half) / (1 + z^2 / n)
```

Both bounds are clamped to [0, 1]; with n = 0 the interval is [0, 0]. One flip in one pair gives a
lower bound of 0.21; 300 flips in 300 pairs give 0.99; 150 in 300 give 0.44.

### Reported, not weighted: runner and hour-of-day correlation

Cramér's V between "failed" and the runner label, and between "failed" and the four-hour bucket of
the build's UTC start time (00-04, 04-08, ...). For a 2 x k table:

```
V = sqrt( chi2 / (n * (min(2, k) - 1)) )
```

V is 0 when there is only one label or only one outcome. It is printed next to the score so a
reader can see "this only fails on the macOS runner" or "only at night", but it is not part of
the score because a correlation with a runner is just as consistent with a real platform bug.

## The score

```
score = 0.5 * wilsonLower(recoveredFailures, failures)
      + 0.3 * wilsonLower(flips, flipPairs)
      + 0.2 * entropyComponent
```

The weights are `Weights.DEFAULT` and can be changed programmatically; the sum of the defaults is
1, so the score is bounded by 1. A test that always passes scores 0. A test that always fails with
the same message also scores 0: that is a bug, not flakiness, and the ranking should not hide it
among the flaky tests.

Ranking is by score, then by number of runs (more evidence first), then by test id.

## Worked example

The fixture case "textbook flaky test with runner and hour skew": ten commits, one build each,
plus two extra attempts of the fourth build.

| Commit | Runs (in order)               | Runner | Hour  |
|--------|-------------------------------|--------|-------|
| c1     | PASS                          | linux  | 14    |
| c2     | FAIL(m2), PASS                | linux  | 02    |
| c3     | PASS                          | mac    | 14    |
| c4     | PASS, FAIL(m9), FAIL(m9)      | linux  | 02-03 |
| c5     | FAIL(m2), PASS                | linux  | 14    |
| c6     | FAIL(m0), PASS                | mac    | 02    |
| c7     | PASS                          | linux  | 14    |
| c8     | PASS                          | linux  | 02    |
| c9     | FAIL(m0), PASS                | mac    | 14    |
| c10    | PASS                          | linux  | 02    |

- 16 runs, 6 failures.
- Rerun recovery: the failures on c2, c5, c6 and c9 were followed by a pass on the same commit;
  the two on c4 were not. `4 / 6 = 0.667`, Wilson lower bound **0.300**.
- Flips: c2, c5, c6 and c9 contribute one pair each, all flips; c4 contributes two pairs, of which
  PASS then FAIL is a flip and FAIL then FAIL is not. `5 / 6 = 0.833`, Wilson interval
  [**0.436**, 0.970].
- Messages: m2 twice, m9 twice, m0 twice over six failures. `H = 3 * (1/3 * log2 3) = 1.585`,
  `entropy = 1.585 / log2 6 = 0.613`, shrunk by `(1 - 1/6)` to **0.511**.
- Runner V = 0.03 (failures are spread over both runners), hour V = 0.16.

```
score = 0.5 * 0.300 + 0.3 * 0.436 + 0.2 * 0.511 = 0.150 + 0.131 + 0.102 = 0.383
```

`FlakeScore.explain()` prints exactly this breakdown:

```
conformance.Suite#case: score 0.383 over 16 run(s)
  rerun recovery   0.300 x 0.5 = 0.150  (4 of 6 failure(s) passed on a retry of the same commit; rate 0.67, 95% interval [0.30, 0.90])
  flip rate        0.436 x 0.3 = 0.131  (5 of 6 consecutive same-commit pair(s) changed outcome; rate 0.83, 95% interval [0.44, 0.97])
  message entropy  0.511 x 0.2 = 0.102  (3 distinct message(s) over 6 failure(s); entropy 0.61, shrunk to 0.51)
  reported only:   runner correlation 0.03, hour-of-day correlation 0.16
```

## What the number means

There is no universal threshold. In practice a score above 0.3 means the test has recovered on
retry or flipped on the same commit often enough that a single failure of it is weak evidence of a
regression; that is the default threshold used by `flake gate`. Between 0.1 and 0.3 the test is
worth watching. The interval bounds, not the point rates, are what keep new tests with one
unlucky run out of the top of the list.
