#pragma endian big

import std.sys;
import std.string;
import std.core;

// Colors for visual highlights
#define COLOR_HEADER      "55AAFF"
#define COLOR_OBJ_NUM     "FFAA00"
#define COLOR_BODY        "CCCCCC"
#define COLOR_STREAM      "FF5555"
#define COLOR_XREF        "AAFF55"
#define COLOR_TRAILER     "FFAAFF"
#define COLOR_EOF         "FF55FF"

// Helper function to check for PDF whitespace
fn is_pdf_whitespace(char ch) {
    return (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n' || ch == '\f' || ch == '\0');
};

// Formatter functions
fn format_pdf_header(ref auto s_hdr) {
    return std::string::to_string(s_hdr.header) + std::string::to_string(s_hdr.version);
};

fn format_pdf_obj(ref auto s_obj) {
    return std::string::to_string(s_obj.object_num) + " " + std::string::to_string(s_obj.generation_num) + " obj";
};

fn format_xref_entry(ref auto s_entry) {
    return std::string::to_string(s_entry.offset) + " " + std::string::to_string(s_entry.generation) + " " + char(s_entry.f_or_n);
};

// Structures matching the 010 Editor templates
struct PDFHeader {
    char header[5] [[color(COLOR_HEADER)]];
    char version[3] [[color(COLOR_HEADER)]];
} [[format("format_pdf_header")]];

struct PDFObject {
    char object_num[] [[color(COLOR_OBJ_NUM)]];
    char ws1[];
    char generation_num[] [[color(COLOR_OBJ_NUM)]];
    char ws2[];
    char obj[3];
    
    char body[]; // Body variable length handled dynamically in layout
    
    // Optional stream components
    if (std::string::to_string(obj) == "obj") {
        // Stream and object body parsing logic
    }
} [[format("format_pdf_obj")]];

struct PDFXRefEntry {
    char offset[10] [[color(COLOR_XREF)]];
    char space1;
    char generation[5] [[color(COLOR_XREF)]];
    char space2;
    u8 f_or_n [[color(COLOR_XREF)]];
} [[format("format_xref_entry")]];

struct PDFTrailer {
    char trailer[7] [[color(COLOR_TRAILER)]];
};

struct PDFStartxref {
    char startxref[9] [[color(COLOR_TRAILER)]];
    char offset[] [[color(COLOR_TRAILER)]];
};

struct PDFEOF {
    char eof[5] [[color(COLOR_EOF)]];
};

// Pattern placement anchor
PDFHeader header @ 0x00;
