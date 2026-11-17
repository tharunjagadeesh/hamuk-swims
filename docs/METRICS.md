# Metrics reference

Every number in Hamuk Swims is computed on the phone by code in `app/src/main/java/com/hamuk/swimpro/analysis/` and `coach/`, and covered by unit tests in `app/src/test/`. If a metric needs data that isn't available (e.g. no lengths or no heart rate), it is simply hidden.

## Pace & time

| Metric | Formula | Notes |
|---|---|---|
| **Workout time** | Watch's active time | Pauses excluded, wall rests included. |
| **Moving time** | Σ length durations | Used only when lengths cover ≥ 80 % of the distance. |
| **Rest %** | (elapsed − moving) ÷ elapsed | |
| **Avg pace** | workout time ÷ distance × 100 | s/100 m, includes rests. |
| **Moving pace** | moving time ÷ distance × 100 | The "real" swimming pace. |
| **Split** | (2nd-half pace − 1st-half pace) ÷ 1st-half pace | Halves by distance. Negative = negative split (good). |
| **Pace variation (CV)** | SD ÷ mean of length paces | < 4 % = very even. |
| **Fade** | last-quarter pace vs first-quarter pace | > 6 % = you started too fast. |
| **Longest non-stop** | longest run of lengths with turns ≤ 10 s | Feeds distance goals. |

## Threshold & zones

- **CSS (Critical Swim Speed)** = (T400 − T200) ÷ 2 per 100 m from a CSS test. A beginner 100 m time trial uses T100 × 1.12.
- Without a test, CSS is estimated as the median recent moving pace − 8 s (or average pace − 10 s if lengths aren't available).
- **Zones** relative to CSS (s/100 m): Z1 +15 · Z2 +9 · Z3 +5 · Z4 +1 · Z5 −4.
- **HR zones**: 50 / 60 / 70 / 80 / 90 % of max HR. Max HR = your override, else the highest HR seen in your swims (if ≥ 150), else 220 − age.

## Load & efficiency

| Metric | Formula |
|---|---|
| **sTSS** | IF³ × hours × 100, with IF = CSS pace ÷ avg pace |
| **TRIMP** (Banister) | minutes × HRr × 0.64 × e^(1.92·HRr), HRr = (avg − rest) ÷ (max − rest) |
| **sRPE** | RPE × minutes |
| **Efficiency (EF)** | (metres per minute) ÷ avg HR |
| **Aerobic drift** | (EF 1st half − EF 2nd half) ÷ EF 1st half, by moving time |
| **SWOLF** | length seconds + strokes |
| **Stroke rate** | strokes ÷ minutes swimming |
| **Distance per stroke** | metres ÷ strokes |

## Training-load model

- Daily load = Σ sTSS of that day's swims.
- **Fitness (CTL)**: CTLₜ = CTLₜ₋₁ + (load − CTLₜ₋₁) ÷ 42.
- **Fatigue (ATL)**: same with ÷ 7.
- **Form (TSB)** = Fitness − Fatigue.
- **ACWR** = mean daily load over 7 days ÷ mean over 28 days. 0.8–1.3 is the sweet spot; above 1.5 is a spike.
  - Caveat: unreliable during roughly the first 4 weeks of data.

## Comparisons & baselines

- **Normal range** = mean ± 1 SD of the metric over the 28 days before a swim (needs ≥ 3 swims).
- **Day and week aggregation:** sums for totals (distance, time, load, kcal); distance-weighted averages for pace, efficiency, SWOLF and split; time-weighted for HR, rest and drift.
- **Better / worse colouring** uses each metric's direction. Changes under 1 % are treated as noise.

## Heart rate vs pace

- **Within a swim:** each length's average HR from samples during that length; least-squares line; lengths more than 2 SD off the line are highlighted.
- **Across swims:** "pace at X bpm" from a 28-day linear fit of avg pace vs avg HR, with X = your median swim HR rounded to 5.
  - The fit is used only if it slopes the right way.
  - Otherwise the app uses the median pace of swims within ±5 bpm of X.

## Lifestyle impact

- **Context per swim:**
  - the sleep that ended that morning
  - the previous day's steps, active kcal and other exercise
  - that morning's resting HR and HRV
  - deviations from each one's 14-day normal
- **Outcomes per swim:**
  - pace vs 28-day normal (%)
  - efficiency vs normal (%)
  - HR residual vs your HR-vs-pace line
  - RPE
- **Link:** Pearson r plus a low/mid/high comparison by factor tertiles. It's reported as a finding only when n ≥ 8 and |r| ≥ 0.3.

## Readiness (0–100)

Starts at 70, then adjusts for:
- sleep vs normal: ±7.5 per hour, capped; −5 extra under 6 h
- resting HR vs normal: −2.5 per bpm
- HRV vs normal: +0.4 per %
- training form: ÷ 2

Labels: ≥ 75 quality day · 55–74 normal · 45–54 moderate · < 45 recovery.

## Goal navigator

- **Time goals:** predicted race time from CSS (+ distance offset) vs target. Required race pace = target ÷ distance.
- **Distance goals:** running best of longest non-stop swims and continuous tests.
- **Checkpoint:** the straight line from start value to target.
- **Projection:** linear trend of actual points to goal day.
- **Daily targets:** from the planned workout's sets at current CSS zones. Easy sets are capped at 70 % of max HR, and readiness < 45 downgrades the day.
