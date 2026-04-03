package com.arc.riskcenter;

/**
 * Tier3Scorer — On-device edge heuristic scorer.
 *
 * Implements the third tier of the three-tier AI pipeline. Runs
 * entirely offline with zero latency using 5 on-chain signals.
 *
 * Formula:
 *   baseline = 40
 *   +10 if holders > 50;  +10 if holders > 100
 *   +15 if top10 < 40%;   +7 if top10 < 50%
 *   +10 if trades5m > 20; +5 if trades5m > 10
 *   +10 if bondingPct in [5%, 75%]
 *   +5  if replyCount > 5
 *   EdgeScore = min(sum, 100)
 *   Gate: EdgeScore ≥ 70 → PASS, ≥ 50 → BORDERLINE, else REJECT
 */
public class Tier3Scorer {

    // Score thresholds matching singularity.py Tier-3 gate logic
    public static final int PASS_THRESHOLD       = 70;
    public static final int BORDERLINE_THRESHOLD = 50;

    /**
     * Produces a 0–100 edge score from 5 on-chain signals.
     * All inputs use -1 as a sentinel for "data unavailable".
     *
     * @param holderCount  number of unique token holders
     * @param top10Pct     % of supply held by top 10 wallets (concentration)
     * @param trades5m     number of trades in the last 5 minutes
     * @param bondingPct   bonding curve completion percentage
     * @param replyCount   community reply count on pump.fun
     */
    public static int score(int holderCount, double top10Pct,
                            int trades5m, double bondingPct, int replyCount) {
        int s = 40; // baseline — must clear several checks to reach PASS

        // Holder distribution (organic spread = good)
        if (holderCount  > 50)  s += 10;
        if (holderCount  > 100) s += 10;

        // Concentration risk (low whale dominance = good)
        if (top10Pct >= 0 && top10Pct < 40) s += 15;
        else if (top10Pct >= 0 && top10Pct < 50) s += 7;

        // Momentum (active trading = good)
        if (trades5m > 20) s += 10;
        else if (trades5m > 10) s += 5;

        // Bonding curve health (not too early, not too close to graduation)
        if (bondingPct >= 0 && bondingPct >= 5 && bondingPct <= 75) s += 10;

        // Community engagement
        if (replyCount > 5) s += 5;

        return Math.min(s, 100);
    }

    /** Human-readable tier label for the dashboard. */
    public static String label(int score) {
        if (score >= PASS_THRESHOLD)       return "T3-Edge: PASS";
        if (score >= BORDERLINE_THRESHOLD) return "T3-Edge: BORDERLINE";
        return "T3-Edge: REJECT";
    }

    /** Colour hint for the UI — matches dashboard green/orange/red scheme. */
    public static String colourHex(int score) {
        if (score >= PASS_THRESHOLD)       return "#4CAF50";
        if (score >= BORDERLINE_THRESHOLD) return "#FF9800";
        return "#F44336";
    }

    /**
     * Returns normalised confidence (0.0–1.0) compatible with singularity.py
     * ai_confidence field. Used when Gemini budget is exhausted.
     */
    public static float toConfidence(int score) {
        return Math.min(score / 100.0f, 1.0f);
    }
}
