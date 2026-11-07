#pragma endian little

enum Kind : u8 { Text = 1, Number = 2, Blob = 0x10 };

bitfield Flags {
    compressed : 1;
    encrypted  : 1;
    padding    : 2;
    level      : 4;
};

struct Record {
    Kind kind;
    u8 length;
    if (kind == Kind::Text) {
        char text[length] [[color("FFAA55")]];
    } else {
        u8 data[length];
    }
};

struct Header {
    char magic[4];
    u16 version;
    Flags flags;
    be u32 recordCount;
    u32 payloadSize = sizeof(Record);
} [[comment("file header")]];

Header header @ 0x00;
Record records[header.recordCount] @ $;
