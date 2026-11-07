package org.exbin.bined.autopsy.hexpat;

import javax.annotation.ParametersAreNonnullByDefault;
import org.exbin.auxiliary.paged_data.BinaryData;

/** Adapts BinEd BinaryData to the engine's ByteSource, turning read failures into pattern errors. */
@ParametersAreNonnullByDefault
public class ContentByteSource implements ByteSource {

    private final BinaryData data;

    public ContentByteSource(BinaryData data) {
        this.data = data;
    }

    @Override
    public long length() {
        return data.getDataSize();
    }

    @Override
    public int get(long position) {
        try {
            return data.getByte(position) & 0xFF;
        } catch (RuntimeException ex) {
            throw new PatternException("cannot read data at 0x" + Long.toHexString(position) + ": " + ex.getMessage(), 0);
        }
    }
}
