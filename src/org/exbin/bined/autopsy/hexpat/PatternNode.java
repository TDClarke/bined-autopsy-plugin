package org.exbin.bined.autopsy.hexpat;

import java.util.ArrayList;
import java.util.List;

/** One placed item (variable, struct, array, enum, bitfield member) in the evaluated pattern. */
public final class PatternNode {
    public final String name;
    public final String typeName;
    public long offset;
    public long size;
    /** Long, Double, String or null for composites. */
    public Object value;
    public String display;
    public String comment;
    public boolean hidden;
    /** 0xRRGGBB or -1 when unset. Explicit [[color]] attribute. */
    public int color = -1;
    /** Colour used for painting (explicit, inherited or generated). */
    public int effectiveColor = -1;
    /** For bitfield members: bit offset inside the bitfield, otherwise -1. */
    public int bitOffset = -1;
    public int bitSize;
    public PatternNode parent;
    public final List<PatternNode> children = new ArrayList<PatternNode>();

    public PatternNode(String name, String typeName) {
        this.name = name;
        this.typeName = typeName;
    }

    public boolean isLeaf() {
        return children.isEmpty();
    }

    public long end() {
        return offset + size;
    }

    @Override
    public String toString() {
        return name;
    }
}
