package org.exbin.bined.autopsy.hexpat;

import java.awt.Color;

/**
 * Maps data positions to pattern colours. Hook this into BinEd's colour assessor
 * (see README) so the hex view highlights the evaluated pattern.
 */
public final class HexPatColorizer {
    private volatile HexPattern.Result result;

    public void setResult(HexPattern.Result result) {
        this.result = result;
    }

    /** @return background colour for the byte at pos, or null if no pattern item covers it. */
    public Color backgroundAt(long pos) {
        HexPattern.Result r = result;
        if (r == null) {
            return null;
        }
        PatternNode n = r.leafAt(pos);
        return n == null || n.effectiveColor < 0 ? null : new Color(n.effectiveColor);
    }
}
