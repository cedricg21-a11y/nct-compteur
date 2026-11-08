package fr.nct.compteur;

/** Sortie simulée pour le mode démo (captures d'écran automatiques, présentation vidéo). */
public class Sim {
    private long t = 0;
    private double dist = 0, alt = 62, hr = 95, lat = 49.0961, lon = 0.4160, brg = 250;
    private final java.util.Random rnd = new java.util.Random(7);
    public String state = "rec";

    private double powerAt(long s) {
        double base = s < 600 ? 150 + s / 10.0 : 205;
        long ph = s % 900;
        if (s > 900 && ph < 180) base = 318;
        else if (s > 900 && ph < 360) base = 160;
        if (s % 1700 > 1640) base = 680;
        return Math.max(0, base + 18 * Math.sin(s / 7.0) + (rnd.nextDouble() - .5) * 30);
    }

    private double gradeAt(double d) {
        double k = (d / 1000) % 9;
        return k > 5 ? 6.5 + 2.5 * Math.sin(d / 400) : .6 * Math.sin(d / 900);
    }

    public Ride.Sample step() {
        Ride.Sample s = new Ride.Sample();
        double p = 0, cad = 0, spd = 0;
        if (state.equals("rec")) {
            t++;
            p = powerAt(t);
            double g = gradeAt(dist);
            spd = Math.max(8, 34 - g * 2.6 + (p - 200) / 25);
            cad = p > 20 ? 86 + (p - 220) / 30 + (rnd.nextDouble() - .5) * 4 : 0;
            dist += spd / 3.6; alt += g / 100 * spd / 3.6;
            hr += (95 + p / 260 * 70 - hr) * .04;
            brg += 4 * Math.sin(t / 40.0);
            double dm = spd / 3.6, r = Math.toRadians(brg);
            lat += dm * Math.cos(r) / 111320.0; lon += dm * Math.sin(r) / (111320.0 * Math.cos(Math.toRadians(lat)));
        }
        s.p = (int) Math.round(p); s.hr = (int) Math.round(hr); s.cad = (int) Math.round(cad);
        s.spd = spd; s.dist = dist; s.alt = alt; s.state = state; s.elapsed = t; s.bat = 87;
        s.sPwr = s.sHr = s.sGps = true;
        s.lat = lat; s.lon = lon; s.bearing = (float) ((brg % 360 + 360) % 360);
        return s;
    }

    public long time() { return t; }
}
