package org.exbin.bined.autopsy.hexpat;

/** Minimal read-only view of the binary data, so the engine does not depend on BinEd classes. */
public interface ByteSource {
    long length();

    /** @return unsigned byte value 0..255 at the position */
    int get(long position);
}
