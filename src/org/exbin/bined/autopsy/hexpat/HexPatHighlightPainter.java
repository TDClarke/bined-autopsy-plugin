package org.exbin.bined.autopsy.hexpat;

import java.awt.Color;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import org.exbin.bined.CodeAreaSection;
import org.exbin.bined.highlight.swing.extended.ExtendedHighlightNonAsciiCodeAreaPainter;
import org.exbin.bined.swing.CodeAreaCore;

/**
 * Code area painter which colours bytes covered by the evaluated .hexpat pattern.
 *
 * Selection and search-match highlighting keep priority over the pattern colours.
 */
@ParametersAreNonnullByDefault
public class HexPatHighlightPainter extends ExtendedHighlightNonAsciiCodeAreaPainter {

    private final HexPatColorizer colorizer;

    public HexPatHighlightPainter(CodeAreaCore codeArea, HexPatColorizer colorizer) {
        super(codeArea);
        this.colorizer = colorizer;
    }

    @Override
    public Color getPositionBackgroundColor(long rowDataPosition, int byteOnRow, int charOnRow,
            CodeAreaSection section, boolean inSelection) {
        Color base = super.getPositionBackgroundColor(rowDataPosition, byteOnRow, charOnRow, section, inSelection);
        if (inSelection) {
            return base;
        }
        if (base != null && (base.equals(getFoundMatchesBackgroundColor()) || base.equals(getCurrentMatchBackgroundColor()))) {
            return base;
        }
        Color pattern = colorizer.backgroundAt(rowDataPosition + byteOnRow);
        return pattern != null ? pattern : base;
    }
}
