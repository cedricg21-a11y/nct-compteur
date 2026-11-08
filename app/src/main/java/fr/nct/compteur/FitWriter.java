package fr.nct.compteur;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Écriture minimale d'un fichier d'activité .FIT (compatible Strava, Garmin Connect, Intervals…).
 * Messages : file_id, event, record, lap, session, activity.
 */
public class FitWriter {
    static final long FIT_EPOCH = 631065600L; // 1989-12-31T00:00:00Z
    static final int[] CRC_TABLE = {0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
            0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400};

    // types de base FIT
    static final int ENUM = 0x00, UINT8 = 0x02, UINT16 = 0x84, SINT32 = 0x85, UINT32 = 0x86, UINT32Z = 0x8C;

    // messages locaux
    static final int L_FILEID = 0, L_EVENT = 1, L_RECORD = 2, L_LAP = 3, L_SESSION = 4, L_ACTIVITY = 5;

    private RandomAccessFile f;
    private final File file;
    private int crc = 0;
    private long dataSize = 0;
    private final byte[] buf = new byte[256];
    private int bp;

    public FitWriter(File file) { this.file = file; }

    public File getFile() { return file; }

    public static long fitTime(long unixMs) { return unixMs / 1000 - FIT_EPOCH; }

    public void open(long startMs) throws IOException {
        f = new RandomAccessFile(file, "rw");
        f.setLength(0);
        f.write(new byte[14]); // en-tête réécrit à la fermeture
        long t = fitTime(startMs);

        define(L_FILEID, 0, new int[][]{{0, 1, ENUM}, {1, 2, UINT16}, {2, 2, UINT16}, {3, 4, UINT32Z}, {4, 4, UINT32}});
        begin(L_FILEID); e8(4); e16(255); e16(1); e32(t & 0xFFFFFFFFL); e32(t); flush();

        define(L_EVENT, 21, new int[][]{{253, 4, UINT32}, {0, 1, ENUM}, {1, 1, ENUM}});
        define(L_RECORD, 20, new int[][]{{253, 4, UINT32}, {0, 4, SINT32}, {1, 4, SINT32}, {2, 2, UINT16},
                {3, 1, UINT8}, {4, 1, UINT8}, {5, 4, UINT32}, {6, 2, UINT16}, {7, 2, UINT16}});
        define(L_LAP, 19, new int[][]{{253, 4, UINT32}, {254, 2, UINT16}, {0, 1, ENUM}, {1, 1, ENUM}, {2, 4, UINT32},
                {7, 4, UINT32}, {8, 4, UINT32}, {9, 4, UINT32}, {19, 2, UINT16}, {15, 1, UINT8}, {17, 1, UINT8}});
        event(startMs, 0);
    }

    /** event_type : 0 = start, 4 = stop_all */
    public void event(long ms, int type) throws IOException {
        begin(L_EVENT); e32(fitTime(ms)); e8(0); e8(type); flush();
    }

    /** lat/lon en degrés (NaN si absent), alt en m (NaN si absent), dist en m, vitesse en m/s. */
    public void record(long ms, double lat, double lon, double alt, int hr, int cad, double distM, double speedMs, int power) throws IOException {
        begin(L_RECORD);
        e32(fitTime(ms));
        if (Double.isNaN(lat)) { e32(0x7FFFFFFFL); e32(0x7FFFFFFFL); }
        else { e32(semi(lat)); e32(semi(lon)); }
        e16(Double.isNaN(alt) ? 0xFFFF : clamp16((alt + 500) * 5));
        e8(hr > 0 ? Math.min(254, hr) : 0xFF);
        e8(cad >= 0 ? Math.min(254, cad) : 0xFF);
        e32(Math.round(distM * 100));
        e16(clamp16(speedMs * 1000));
        e16(power >= 0 ? Math.min(65534, power) : 0xFFFF);
        flush();
        recCount++;
    }
    int recCount;

    public void lap(int index, long startMs, long endMs, double timerS, double distM, int avgP, int avgHr, int avgCad) throws IOException {
        begin(L_LAP);
        e32(fitTime(endMs)); e16(index); e8(9); e8(1); e32(fitTime(startMs));
        e32(Math.round((endMs - startMs))); e32(Math.round(timerS * 1000)); e32(Math.round(distM * 100));
        e16(avgP >= 0 ? avgP : 0xFFFF); e8(avgHr > 0 ? avgHr : 0xFF); e8(avgCad > 0 ? avgCad : 0xFF);
        flush();
    }

    public void close(long startMs, long endMs, double timerS, double distM, int avgP, int avgHr, int numLaps) throws IOException {
        event(endMs, 4);
        define(L_SESSION, 18, new int[][]{{253, 4, UINT32}, {0, 1, ENUM}, {1, 1, ENUM}, {2, 4, UINT32}, {5, 1, ENUM}, {6, 1, ENUM},
                {7, 4, UINT32}, {8, 4, UINT32}, {9, 4, UINT32}, {20, 2, UINT16}, {16, 1, UINT8}, {25, 2, UINT16}, {26, 2, UINT16}});
        begin(L_SESSION);
        e32(fitTime(endMs)); e8(8); e8(1); e32(fitTime(startMs)); e8(2); e8(7);
        e32(endMs - startMs); e32(Math.round(timerS * 1000)); e32(Math.round(distM * 100));
        e16(avgP >= 0 ? avgP : 0xFFFF); e8(avgHr > 0 ? avgHr : 0xFF); e16(0); e16(numLaps);
        flush();

        define(L_ACTIVITY, 34, new int[][]{{253, 4, UINT32}, {0, 4, UINT32}, {1, 2, UINT16}, {2, 1, ENUM}, {3, 1, ENUM}, {4, 1, ENUM}, {5, 4, UINT32}});
        long tz = java.util.TimeZone.getDefault().getOffset(endMs) / 1000;
        begin(L_ACTIVITY);
        e32(fitTime(endMs)); e32(Math.round(timerS * 1000)); e16(1); e8(0); e8(26); e8(1); e32(fitTime(endMs) + tz);
        flush();

        // CRC du fichier (en-tête + données)
        int dataCrc = crc;
        // en-tête
        byte[] h = new byte[14];
        h[0] = 14; h[1] = 0x20; h[2] = (byte) (2132 & 0xFF); h[3] = (byte) (2132 >> 8);
        h[4] = (byte) dataSize; h[5] = (byte) (dataSize >> 8); h[6] = (byte) (dataSize >> 16); h[7] = (byte) (dataSize >> 24);
        h[8] = '.'; h[9] = 'F'; h[10] = 'I'; h[11] = 'T';
        int hc = 0; for (int i = 0; i < 12; i++) hc = crc16(hc, h[i]);
        h[12] = (byte) hc; h[13] = (byte) (hc >> 8);
        f.seek(0); f.write(h);
        // CRC final = CRC sur en-tête complet puis données
        int fc = 0; for (byte b : h) fc = crc16(fc, b);
        f.seek(14);
        byte[] chunk = new byte[8192]; long left = dataSize; int n;
        while (left > 0 && (n = f.read(chunk, 0, (int) Math.min(chunk.length, left))) > 0) {
            for (int i = 0; i < n; i++) fc = crc16(fc, chunk[i]);
            left -= n;
        }
        f.seek(14 + dataSize);
        f.write(new byte[]{(byte) fc, (byte) (fc >> 8)});
        f.close();
    }

    public void abort() { try { if (f != null) f.close(); } catch (IOException ignored) {} file.delete(); }

    // ------------------------------------------------------------------

    private void define(int local, int global, int[][] fields) throws IOException {
        bp = 0;
        buf[bp++] = (byte) (0x40 | local); buf[bp++] = 0; buf[bp++] = 0;
        buf[bp++] = (byte) global; buf[bp++] = (byte) (global >> 8);
        buf[bp++] = (byte) fields.length;
        for (int[] fd : fields) { buf[bp++] = (byte) fd[0]; buf[bp++] = (byte) fd[1]; buf[bp++] = (byte) fd[2]; }
        flush();
    }

    private void begin(int local) { bp = 0; buf[bp++] = (byte) local; }
    private void e8(long v) { buf[bp++] = (byte) v; }
    private void e16(long v) { buf[bp++] = (byte) v; buf[bp++] = (byte) (v >> 8); }
    private void e32(long v) { buf[bp++] = (byte) v; buf[bp++] = (byte) (v >> 8); buf[bp++] = (byte) (v >> 16); buf[bp++] = (byte) (v >> 24); }

    private void flush() throws IOException {
        f.write(buf, 0, bp);
        for (int i = 0; i < bp; i++) crc = crc16(crc, buf[i]);
        dataSize += bp;
    }

    static int crc16(int crc, byte b) {
        int tmp = CRC_TABLE[crc & 0xF];
        crc = (crc >> 4) & 0x0FFF;
        crc = crc ^ tmp ^ CRC_TABLE[b & 0xF];
        tmp = CRC_TABLE[crc & 0xF];
        crc = (crc >> 4) & 0x0FFF;
        crc = crc ^ tmp ^ CRC_TABLE[(b >> 4) & 0xF];
        return crc;
    }

    static long semi(double deg) { return Math.round(deg * (2147483648.0 / 180.0)); }
    static long clamp16(double v) { long x = Math.round(v); return x < 0 ? 0 : x > 65534 ? 65534 : x; }
}
