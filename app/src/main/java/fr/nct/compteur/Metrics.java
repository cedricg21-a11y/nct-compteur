package fr.nct.compteur;

import java.util.ArrayList;

/** Moteur de métriques : NP, IF, TSS, W'bal, zones, pente, VAM, tours. */
public class Metrics {
    public int ftp = 250, hrMax = 185;
    public double weight = 72, wPrime = 20000;

    public static final String[] ZN = {"Z1", "Z2", "Z3", "Z4", "Z5", "Z6", "Z7"};
    public static final String[] ZNAME = {"RÉCUP", "ENDURANCE", "TEMPO", "SEUIL", "PMA", "ANAÉROBIE", "SPRINT"};
    public static final double[] ZMAX = {0.55, 0.75, 0.90, 1.05, 1.20, 1.50, 99};
    public static final int[] ZCOL = {0xFF5F7A94, 0xFF00E5C8, 0xFF9BE36A, 0xFFFFD23E, 0xFFFFB02E, 0xFFFF7A2E, 0xFFE85022};

    public static class Lap {
        public int n = 1; public long t; public double dist0, dist;
        double pSum, p4Sum, hrSum, cSum; int pN, p4N, hrN, cN;
        double[] roll = new double[30]; int rollN, rollI;
        public double ap() { return pN > 0 ? pSum / pN : 0; }
        public double np() { return p4N > 0 ? Math.pow(p4Sum / p4N, 0.25) : ap(); }
        public double hr() { return hrN > 0 ? hrSum / hrN : 0; }
        public double cad() { return cN > 0 ? cSum / cN : 0; }
        public double spd() { return t > 0 ? dist / t * 3.6 : 0; }
    }

    public static class Pt { public double d, a; Pt(double d, double a) { this.d = d; this.a = a; } }

    // état
    public long t, moving;
    double[] pwr = new double[60]; int pwrN, pwrI;
    double[] r30 = new double[30]; int r30N, r30I;
    double p4Sum; int p4N;
    public double kj, wbal, dist, dplus, grade;
    public long[] zoneSec = new long[7];
    double cadSum; int cadN;
    Double lastAlt;
    final ArrayList<double[]> altHist = new ArrayList<>(); // {t, d, a}
    public final ArrayList<Pt> prof = new ArrayList<>();
    public Lap lap = new Lap(), prevLap;
    public final ArrayList<Lap> laps = new ArrayList<>();
    public double maxPower, pTot; public long pCnt;
    // montée en cours
    public boolean inClimb; double climbD0, climbA0, climbEndRun; double climbLastA, climbGainAcc;

    public Metrics() { reset(); }

    public void reset() {
        t = moving = 0; pwrN = pwrI = 0; r30N = r30I = 0; p4Sum = 0; p4N = 0;
        kj = 0; wbal = wPrime; dist = 0; dplus = 0; grade = 0; zoneSec = new long[7];
        cadSum = 0; cadN = 0; lastAlt = null; altHist.clear(); prof.clear();
        lap = new Lap(); prevLap = null; laps.clear();
        maxPower = 0; pTot = 0; pCnt = 0; inClimb = false;
    }

    public int zoneOf(double p) {
        double r = p / ftp;
        for (int i = 0; i < 7; i++) if (r < ZMAX[i]) return i;
        return 6;
    }

    static double avgRing(double[] a, int n, int idx, int k) {
        k = Math.min(k, n); if (k == 0) return 0;
        double s = 0; for (int i = 0; i < k; i++) s += a[((idx - 1 - i) % a.length + a.length) % a.length];
        return s / k;
    }

    public double p3() { return avgRing(pwr, pwrN, pwrI, 3); }
    public double p30() { return avgRing(pwr, pwrN, pwrI, 30); }

    public void push(double p, int hr, int cad, double spd, double alt, double distM, boolean rec) {
        pwr[pwrI] = p; pwrI = (pwrI + 1) % 60; pwrN = Math.min(60, pwrN + 1);
        if (!rec) return;
        t++;
        if (spd > 2) moving++;
        pTot += p; pCnt++; if (p > maxPower) maxPower = p;
        kj += p / 1000;
        zoneSec[zoneOf(p)]++;
        if (cad > 0) { cadSum += cad; cadN++; }

        r30[r30I] = p; r30I = (r30I + 1) % 30; r30N = Math.min(30, r30N + 1);
        if (r30N == 30) { p4Sum += Math.pow(avgRing(r30, 30, r30I, 30), 4); p4N++; }

        double cp = ftp, W = wPrime;
        if (p < cp) wbal += (cp - p) * (W - wbal) / W; else wbal -= (p - cp);
        wbal = Math.max(0, Math.min(W, wbal));

        dist = distM;
        if (!Double.isNaN(alt)) {
            if (lastAlt == null) lastAlt = alt;
            double d = alt - lastAlt;
            if (Math.abs(d) >= 1) { if (d > 0) dplus += d; lastAlt = alt; }
            altHist.add(new double[]{t, dist, alt});
            if (altHist.size() > 600) altHist.remove(0);
            for (int i = altHist.size() - 1; i >= 0; i--) {
                double dd = dist - altHist.get(i)[1];
                if (dd >= 60) { grade = 100 * (alt - altHist.get(i)[2]) / dd; break; }
            }
            // détection de montée : > 3 % ; fin quand la pente reste < 1 % sur 200 m
            if (!inClimb && grade >= 3) { inClimb = true; climbD0 = dist; climbA0 = alt; climbLastA = alt; climbGainAcc = 0; climbEndRun = dist; }
            if (inClimb) {
                if (alt > climbLastA) climbGainAcc += alt - climbLastA;
                climbLastA = alt;
                if (grade >= 1) climbEndRun = dist;
                else if (dist - climbEndRun > 200) inClimb = false;
            }
            Pt lp = prof.isEmpty() ? null : prof.get(prof.size() - 1);
            if (lp == null || dist - lp.d >= 20) prof.add(new Pt(dist, alt));
            while (!prof.isEmpty() && dist - prof.get(0).d > 5000) prof.remove(0);
        }

        Lap L = lap;
        L.t++; L.dist = dist - L.dist0;
        L.pSum += p; L.pN++;
        L.roll[L.rollI] = p; L.rollI = (L.rollI + 1) % 30; L.rollN = Math.min(30, L.rollN + 1);
        if (L.rollN == 30) { L.p4Sum += Math.pow(avgRing(L.roll, 30, L.rollI, 30), 4); L.p4N++; }
        if (hr > 0) { L.hrSum += hr; L.hrN++; }
        if (cad > 0) { L.cSum += cad; L.cN++; }
    }

    public double avgPower() { return pCnt > 0 ? pTot / pCnt : 0; }
    public double climbDist() { return inClimb ? dist - climbD0 : 0; }
    public double climbGain() { return inClimb ? climbGainAcc : 0; }
    public double climbAvgGrade() { double d = climbDist(); return d > 20 ? 100 * (climbLastA - climbA0) / d : 0; }

    public Lap newLap() {
        prevLap = lap;
        laps.add(lap);
        Lap n = new Lap(); n.n = lap.n + 1; n.dist0 = dist;
        lap = n;
        return prevLap;
    }

    public double np() { return p4N > 0 ? Math.pow(p4Sum / p4N, 0.25) : avgRing(r30, r30N, r30I, 30); }
    public double IF() { return np() / ftp; }
    public double tss() { double n = np(); return (t * n * (n / ftp)) / (ftp * 3600.0) * 100; }
    public double wbalPct() { return 100 * wbal / wPrime; }
    public double cadAvg() { return cadN > 0 ? cadSum / cadN : 0; }
    public double spdAvg() { return moving > 0 ? dist / moving * 3.6 : 0; }

    public double vam() {
        int n = altHist.size(); if (n < 20) return 0;
        int win = Math.min(300, n - 1);
        double prev = altHist.get(n - 1 - win)[2], up = 0;
        for (int i = n - win; i < n; i++) { double d = altHist.get(i)[2] - prev; if (d > 0) up += d; prev = altHist.get(i)[2]; }
        return up * 3600 / win;
    }
}
