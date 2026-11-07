package org.exbin.bined.autopsy.hexpat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser and evaluator for a practical subset of the ImHex pattern language (.hexpat).
 *
 * Supported: primitive types, struct/union, enum, bitfield, using, arrays [N],
 * placement (@), if/else, local variables and assignments, $, sizeof/addressof,
 * be/le, #pragma endian, attributes [[color, name, comment, hidden]], a few std:: functions.
 * Not supported: templates, inheritance, pointers, functions (fn bodies are skipped),
 * while-arrays, imports.
 *
 * Java 8 compatible, no dependency on BinEd.
 */
public final class HexPattern {

    private HexPattern() {
    }

    // ------------------------------------------------------------------ public API

    public static final class Result {
        public final PatternNode root = new PatternNode("<root>", "root");
        public final List<String> errors = new ArrayList<String>();
        public final List<String> log = new ArrayList<String>();
        private PatternNode[] leaves;

        /** Leaf nodes sorted by offset. */
        public PatternNode[] leaves() {
            if (leaves == null) {
                List<PatternNode> list = new ArrayList<PatternNode>();
                collect(root, list);
                Collections.sort(list, new Comparator<PatternNode>() {
                    @Override
                    public int compare(PatternNode a, PatternNode b) {
                        return a.offset < b.offset ? -1 : (a.offset > b.offset ? 1 : 0);
                    }
                });
                leaves = list.toArray(new PatternNode[0]);
            }
            return leaves;
        }

        private static void collect(PatternNode n, List<PatternNode> out) {
            if (n.isLeaf()) {
                if (n.size > 0 && n != null) {
                    out.add(n);
                }
            } else {
                for (PatternNode c : n.children) {
                    collect(c, out);
                }
            }
        }

        /** Leaf covering the position, or null. */
        public PatternNode leafAt(long pos) {
            PatternNode[] l = leaves();
            int lo = 0, hi = l.length - 1, idx = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (l[mid].offset <= pos) {
                    idx = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            for (int i = idx; i >= 0 && i > idx - 8; i--) {
                if (pos >= l[i].offset && pos < l[i].end()) {
                    return l[i];
                }
            }
            return null;
        }

        /** Deepest chain from root to the leaf at the position (for tree selection). */
        public List<PatternNode> pathAt(long pos) {
            PatternNode leaf = leafAt(pos);
            List<PatternNode> path = new ArrayList<PatternNode>();
            for (PatternNode n = leaf; n != null; n = n.parent) {
                path.add(0, n);
            }
            return path;
        }
    }

    /** Parses and evaluates the pattern against the data. Never throws; problems are in Result.errors. */
    public static Result run(String source, ByteSource data) {
        Result result = new Result();
        Program program;
        try {
            program = new Parser(lex(source)).parseProgram();
        } catch (PatternException ex) {
            result.errors.add("Syntax error, " + ex);
            return result;
        }
        for (String w : program.warnings) {
            result.log.add("warning: " + w);
        }
        Interp in = new Interp(data, program.types, result.log);
        in.functions = program.functions;
        in.big = program.bigEndian;
        Scope top = new Scope(result.root, null);
        try {
            for (Stmt s : program.stmts) {
                s.exec(top, in);
            }
        } catch (PatternException ex) {
            result.errors.add("Runtime error, " + ex);
        } catch (StackOverflowError ex) {
            result.errors.add("Runtime error: recursion too deep");
        }
        assignColors(result.root, -1);
        return result;
    }

    // ------------------------------------------------------------------ colours

    private static final int[] PALETTE = {0xF4B6B6, 0xB6D4F4, 0xB6F4C0, 0xF4E3B6, 0xD5B6F4, 0xB6F4EE,
        0xF4B6E0, 0xD0F4B6, 0xF4CDB6, 0xB6BCF4, 0xE6E6A0, 0xA0E6D2};

    private static void assignColors(PatternNode n, int inherited) {
        int c = n.color != -1 ? n.color : inherited;
        if (n.isLeaf()) {
            if (c == -1) {
                String key = n.name.replaceAll("\\[\\d+\\]", "");
                c = PALETTE[(key.hashCode() & 0x7fffffff) % PALETTE.length];
            }
            n.effectiveColor = c;
        } else {
            n.effectiveColor = c;
            for (PatternNode ch : n.children) {
                assignColors(ch, c);
            }
        }
    }

    // ------------------------------------------------------------------ lexer

    private enum K {
        ID, NUM, STR, CHR, OP, PRAGMA, EOF
    }

    private static final class Tok {
        final K k;
        final String s;
        final Object v;
        final int line;

        Tok(K k, String s, Object v, int line) {
            this.k = k;
            this.s = s;
            this.v = v;
            this.line = line;
        }
    }

    private static final List<String> OPS2 = Arrays.asList("<<", ">>", "<=", ">=", "==", "!=", "&&", "||", "+=", "-=");

    private static boolean idStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean idPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static List<Tok> lex(String src) {
        return lex(src, new HashMap<String, List<Tok>>());
    }

    private static List<Tok> lex(String src, Map<String, List<Tok>> defines) {
        List<Tok> out = new ArrayList<Tok>();
        int i = 0, n = src.length(), line = 1;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\n') {
                line++;
                i++;
            } else if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int e = src.indexOf("*/", i + 2);
                if (e < 0) {
                    e = n - 2;
                }
                for (int j = i; j < e; j++) {
                    if (src.charAt(j) == '\n') {
                        line++;
                    }
                }
                i = e + 2;
            } else if (c == '#') {
                int e = src.indexOf('\n', i);
                if (e < 0) {
                    e = n;
                }
                String l = src.substring(i, e).trim();
                if (l.startsWith("#pragma")) {
                    out.add(new Tok(K.PRAGMA, l.substring(7).trim(), null, line));
                } else if (l.startsWith("#define")) {
                    String[] parts = l.substring(7).trim().split("\\s+", 2);
                    if (parts.length >= 1 && !parts[0].isEmpty() && parts[0].indexOf('(') < 0) {
                        List<Tok> body = parts.length > 1 ? lex(parts[1], defines) : new ArrayList<Tok>();
                        if (!body.isEmpty()) {
                            body.remove(body.size() - 1); // end-of-file marker
                        }
                        defines.put(parts[0], body);
                    }
                }
                i = e;
            } else if (Character.isDigit(c)) {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(src.charAt(j)) || src.charAt(j) == '_'
                        || (src.charAt(j) == '.' && j + 1 < n && Character.isDigit(src.charAt(j + 1))))) {
                    j++;
                }
                String t = src.substring(i, j).replace("_", "");
                try {
                    Object v;
                    String low = t.toLowerCase();
                    if (low.startsWith("0x")) {
                        v = Long.parseUnsignedLong(stripSuffix(low.substring(2)), 16);
                    } else if (low.startsWith("0b")) {
                        v = Long.parseUnsignedLong(stripSuffix(low.substring(2)), 2);
                    } else if (low.contains(".")) {
                        v = Double.parseDouble(low.replaceAll("[fd]$", ""));
                    } else {
                        v = Long.parseUnsignedLong(stripSuffix(low));
                    }
                    out.add(new Tok(K.NUM, t, v, line));
                } catch (NumberFormatException ex) {
                    throw new PatternException("bad number literal '" + t + "'", line);
                }
                i = j;
            } else if (idStart(c)) {
                int j = i + 1;
                while (j < n) {
                    if (idPart(src.charAt(j))) {
                        j++;
                    } else if (src.charAt(j) == ':' && j + 2 < n && src.charAt(j + 1) == ':' && idStart(src.charAt(j + 2))) {
                        j += 2;
                    } else {
                        break;
                    }
                }
                String word = src.substring(i, j);
                List<Tok> macro = defines.get(word);
                if (macro != null) {
                    for (Tok m : macro) {
                        out.add(new Tok(m.k, m.s, m.v, line));
                    }
                } else {
                    out.add(new Tok(K.ID, word, null, line));
                }
                i = j;
            } else if (c == '"' || c == '\'') {
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                while (j < n && src.charAt(j) != c) {
                    char d = src.charAt(j);
                    if (d == '\\' && j + 1 < n) {
                        j++;
                        char e = src.charAt(j);
                        if (e == 'x' && j + 2 < n && isHex(src.charAt(j + 1)) && isHex(src.charAt(j + 2))) {
                            d = (char) Integer.parseInt(src.substring(j + 1, j + 3), 16);
                            j += 2;
                        } else {
                            d = e == 'n' ? '\n' : e == 't' ? '\t' : e == 'r' ? '\r' : e == '0' ? '\0' : e;
                        }
                    }
                    sb.append(d);
                    j++;
                }
                if (j >= n) {
                    throw new PatternException("unterminated literal", line);
                }
                if (c == '"') {
                    out.add(new Tok(K.STR, sb.toString(), sb.toString(), line));
                } else {
                    out.add(new Tok(K.CHR, sb.toString(), (long) (sb.length() > 0 ? sb.charAt(0) : 0), line));
                }
                i = j + 1;
            } else {
                String two = i + 1 < n ? src.substring(i, i + 2) : "";
                if (OPS2.contains(two)) {
                    out.add(new Tok(K.OP, two, null, line));
                    i += 2;
                } else {
                    out.add(new Tok(K.OP, String.valueOf(c), null, line));
                    i++;
                }
            }
        }
        out.add(new Tok(K.EOF, "<end of file>", null, line));
        return out;
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }

    private static String stripSuffix(String s) {
        return s.replaceAll("[ul]+$", "");
    }

    // ------------------------------------------------------------------ runtime model

    private static final class Scope {
        final PatternNode node;
        final Scope up;
        final Map<String, Object> vars = new HashMap<String, Object>();

        Scope(PatternNode node, Scope up) {
            this.node = node;
            this.up = up;
        }

        static final Object MISSING = new Object();

        Object lookup(String name) {
            for (Scope s = this; s != null; s = s.up) {
                if (s.vars.containsKey(name)) {
                    return s.vars.get(name);
                }
                if (s.node != null) {
                    for (int i = s.node.children.size() - 1; i >= 0; i--) {
                        if (s.node.children.get(i).name.equals(name)) {
                            return s.node.children.get(i);
                        }
                    }
                }
            }
            return MISSING;
        }

        boolean assign(String name, Object v) {
            for (Scope s = this; s != null; s = s.up) {
                if (s.vars.containsKey(name)) {
                    s.vars.put(name, v);
                    return true;
                }
            }
            return false;
        }
    }

    private interface Expr {
        Object eval(Scope sc, Interp in);
    }

    private interface Stmt {
        void exec(Scope sc, Interp in);
    }

    private static final Stmt NOP = new Stmt() {
        @Override
        public void exec(Scope sc, Interp in) {
        }
    };

    private static final class TypeRef {
        final String name;
        final int endian; // 0 default, 1 little, 2 big
        final String arg; // string template argument, e.g. type::Magic<"JFIF">

        TypeRef(String name, int endian) {
            this(name, endian, null);
        }

        TypeRef(String name, int endian, String arg) {
            this.name = name;
            this.endian = endian;
            this.arg = arg;
        }
    }

    private static final class Prim {
        final int size;
        final char kind; // u s f c b

        Prim(int size, char kind) {
            this.size = size;
            this.kind = kind;
        }
    }

    private static final Map<String, Prim> PRIMS = new HashMap<String, Prim>();

    static {
        int[] sizes = {8, 16, 24, 32, 48, 64};
        for (int bits : sizes) {
            PRIMS.put("u" + bits, new Prim(bits / 8, 'u'));
            PRIMS.put("s" + bits, new Prim(bits / 8, 's'));
        }
        PRIMS.put("float", new Prim(4, 'f'));
        PRIMS.put("double", new Prim(8, 'f'));
        PRIMS.put("char", new Prim(1, 'c'));
        PRIMS.put("char16", new Prim(2, 'c'));
        PRIMS.put("bool", new Prim(1, 'b'));
    }

    private abstract static class TypeDef {
        abstract PatternNode make(Interp in, String name, Scope up, PatternNode parent);
    }

    private static final class Program {
        final List<Stmt> stmts = new ArrayList<Stmt>();
        final Map<String, TypeDef> types = new HashMap<String, TypeDef>();
        final List<String> warnings = new ArrayList<String>();
        final Map<String, FuncDef> functions = new HashMap<String, FuncDef>();
        boolean bigEndian;
    }

    private static final class FuncDef {
        final List<String> params;
        final List<Stmt> body;

        FuncDef(List<String> params, List<Stmt> body) {
            this.params = params;
            this.body = body;
        }
    }

    private static final class ReturnSignal extends RuntimeException {
        final transient Object value;

        ReturnSignal(Object value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    /** Unsized array T x[]; read until an all-zero element (included), like ImHex. */
    private static final class UntilNullDim implements Expr {
        @Override
        public Object eval(Scope sc, Interp in) {
            throw new PatternException("unsized array used as a number", 0);
        }
    }

    /** Array size of the form [while(condition)]. */
    private static final class WhileDim implements Expr {
        final Expr cond;

        WhileDim(Expr cond) {
            this.cond = cond;
        }

        @Override
        public Object eval(Scope sc, Interp in) {
            throw new PatternException("while-condition used as a number", 0);
        }
    }

    private static final class Interp {
        static final long MAX_NODES = 3000000L;
        final ByteSource src;
        final Map<String, TypeDef> types;
        final List<String> log;
        Map<String, FuncDef> functions = new HashMap<String, FuncDef>();
        long cursor;
        boolean big;
        int depth;
        int callDepth;
        long nodes;

        Interp(ByteSource src, Map<String, TypeDef> types, List<String> log) {
            this.src = src;
            this.types = types;
            this.log = log;
        }

        PatternNode newNode(String name, String type, PatternNode parent) {
            if (++nodes > MAX_NODES) {
                throw new PatternException("pattern produced too many items (limit " + MAX_NODES + ")", 0);
            }
            PatternNode n = new PatternNode(name, type);
            n.parent = parent;
            n.offset = cursor;
            if (parent != null) {
                parent.children.add(n); // attach at once: if evaluation fails later, the partial tree is kept
            }
            return n;
        }

        long readUInt(long pos, int size, boolean be) {
            if (pos < 0 || pos + size > src.length()) {
                throw new PatternException("reading " + size + " byte(s) at 0x" + Long.toHexString(pos)
                        + " is past the end of the data (0x" + Long.toHexString(src.length()) + ")", 0);
            }
            long v = 0;
            for (int i = 0; i < size; i++) {
                int b = src.get(pos + (be ? i : size - 1 - i));
                v = (v << 8) | b;
            }
            return v;
        }

        PatternNode readMagic(TypeRef t, String name, PatternNode parent) {
            if (t.arg == null) {
                throw new PatternException("type::Magic needs a string, like type::Magic<\"ABCD\">", 0);
            }
            PatternNode n = newNode(name, "Magic", parent);
            n.size = t.arg.length();
            StringBuilder shown = new StringBuilder();
            boolean same = cursor >= 0 && cursor + n.size <= src.length();
            for (int i = 0; i < t.arg.length(); i++) {
                char want = t.arg.charAt(i);
                shown.append(want >= 32 && want < 127 ? String.valueOf(want) : String.format("\\x%02X", (int) want));
                if (same && src.get(cursor + i) != (want & 0xFF)) {
                    same = false;
                }
            }
            if (cursor + n.size > src.length()) {
                throw new PatternException("magic value at 0x" + Long.toHexString(cursor) + " is past the end of the data", 0);
            }
            n.value = t.arg;
            n.display = "\"" + shown + "\"" + (same ? "" : "  (MISMATCH)");
            if (!same) {
                log.add("warning: magic \"" + shown + "\" does not match at 0x" + Long.toHexString(cursor).toUpperCase());
            }
            cursor += n.size;
            return n;
        }

        PatternNode place(TypeRef t, String name, Scope sc, PatternNode parent) {
            if (t.name.equals("type::Magic")) {
                return readMagic(t, name, parent);
            }
            TypeDef td = types.get(t.name);
            if (td != null) {
                boolean saved = big;
                if (t.endian != 0) {
                    big = t.endian == 2;
                }
                try {
                    return td.make(this, name, sc, parent);
                } finally {
                    big = saved;
                }
            }
            Prim p = PRIMS.get(t.name);
            if (p == null) {
                throw new PatternException("unknown type '" + t.name + "'", 0);
            }
            boolean be = t.endian != 0 ? t.endian == 2 : big;
            return readPrim(p, t.name, name, be, parent);
        }

        PatternNode readPrim(Prim p, String typeName, String name, boolean be, PatternNode parent) {
            PatternNode n = newNode(name, typeName, parent);
            n.size = p.size;
            long raw = readUInt(cursor, p.size, be);
            switch (p.kind) {
                case 's': {
                    int sh = 64 - 8 * p.size;
                    long v = (raw << sh) >> sh;
                    n.value = v;
                    n.display = v + " (0x" + Long.toHexString(raw).toUpperCase() + ")";
                    break;
                }
                case 'f': {
                    double d = p.size == 4 ? Float.intBitsToFloat((int) raw) : Double.longBitsToDouble(raw);
                    n.value = d;
                    n.display = String.valueOf(d);
                    break;
                }
                case 'c': {
                    n.value = raw;
                    char ch = (char) raw;
                    n.display = "'" + (ch < 32 || ch > 126 ? "." : String.valueOf(ch)) + "' (0x"
                            + Long.toHexString(raw).toUpperCase() + ")";
                    break;
                }
                case 'b': {
                    n.value = raw != 0 ? 1L : 0L;
                    n.display = raw != 0 ? "true" : "false";
                    break;
                }
                default: {
                    n.value = raw;
                    n.display = Long.toUnsignedString(raw) + " (0x" + Long.toHexString(raw).toUpperCase() + ")";
                }
            }
            cursor += p.size;
            return n;
        }

        PatternNode placeArray(TypeRef t, String name, Scope sc, PatternNode parent, List<Expr> dims, int dim) {
            Expr d = dims.get(dim);
            boolean until = d instanceof WhileDim;
            boolean untilNull = d instanceof UntilNullDim;
            boolean last = dim == dims.size() - 1;
            Prim prim = types.containsKey(t.name) ? null : PRIMS.get(t.name);
            long count = -1;
            if (!until && !untilNull) {
                count = num(unwrap(d.eval(sc, this)));
                if (count < 0 || count > 100000000L) {
                    throw new PatternException("invalid array size " + count + (count < 0
                            ? " (negative: an end marker or length was probably not found, the file may be truncated or damaged)" : ""), 0);
                }
            }
            PatternNode arr = newNode(name, t.name + "[" + (until || untilNull ? "" : String.valueOf(count)) + "]", parent);
            boolean isChars = last && prim != null && prim.kind == 'c' && prim.size == 1;

            // big primitive arrays are kept as one item (no per-element nodes), like [[sealed]] in ImHex
            if (!until && !untilNull && last && prim != null && count > 1024) {
                long bytes = count * prim.size;
                if (cursor < 0 || cursor + bytes > src.length()) {
                    throw new PatternException("array of " + count + " items at 0x" + Long.toHexString(cursor)
                            + " runs past the end of the data", 0);
                }
                arr.size = bytes;
                if (isChars) {
                    StringBuilder text = new StringBuilder();
                    for (long k = 0; k < Math.min(count, 256); k++) {
                        int b = src.get(cursor + k);
                        if (b == 0) {
                            break;
                        }
                        text.append((char) b);
                    }
                    arr.value = text.toString();
                    arr.display = "\"" + text.toString().replaceAll("[^\\x20-\\x7E]", ".") + "...\"";
                } else {
                    arr.display = "[" + count + " items]";
                }
                cursor += bytes;
                return arr;
            }

            StringBuilder text = isChars ? new StringBuilder() : null;
            long i = 0;
            try {
            while (true) {
                if (until) {
                    if (!truthy(((WhileDim) d).cond.eval(sc, this))) {
                        break;
                    }
                } else if (!untilNull && i >= count) {
                    break;
                }
                long before = cursor;
                PatternNode e;
                String en = name + "[" + i + "]";
                if (dim + 1 < dims.size()) {
                    e = placeArray(t, en, sc, arr, dims, dim + 1);
                } else {
                    e = place(t, en, sc, arr);
                }
                i++;
                if ((until || untilNull) && cursor == before) {
                    throw new PatternException("element of an unsized or [while] array has size 0, stopping", 0);
                }
                if (isChars && text.length() <= 4096 && e.value instanceof Long) {
                    text.append((char) ((Long) e.value).longValue());
                }
                if (untilNull && isNullElement(e)) {
                    break;
                }
            }
            } finally {
                arr.size = cursor - arr.offset;
                if (arr.display == null) {
                    arr.display = "[" + i + " items, incomplete]";
                }
            }
            if (isChars) {
                String str = text.toString();
                int z = str.indexOf('\0');
                if (z >= 0) {
                    str = str.substring(0, z);
                }
                arr.value = str;
                arr.display = "\"" + str.replaceAll("[^\\x20-\\x7E]", ".") + "\"";
            } else {
                arr.display = "[" + i + " items]";
            }
            return arr;
        }

        boolean isNullElement(PatternNode e) {
            if (e.value instanceof Long) {
                return (Long) e.value == 0L;
            }
            if (e.value instanceof Double) {
                return (Double) e.value == 0.0;
            }
            for (long k = 0; k < e.size && k < 65536; k++) {
                if (src.get(e.offset + k) != 0) {
                    return false;
                }
            }
            return e.size > 0;
        }

        long sizeOfType(String name) {
            Prim p = PRIMS.get(name);
            if (p != null) {
                return p.size;
            }
            TypeDef td = types.get(name);
            if (td == null) {
                throw new PatternException("unknown type '" + name + "'", 0);
            }
            Interp dry = new Interp(new ByteSource() {
                @Override
                public long length() {
                    return Long.MAX_VALUE / 4;
                }

                @Override
                public int get(long position) {
                    return 0;
                }
            }, types, new ArrayList<String>());
            dry.functions = functions;
            PatternNode n = td.make(dry, "tmp", new Scope(new PatternNode("<tmp>", "tmp"), null), null);
            return n.size;
        }
    }

    // ------------------------------------------------------------------ value helpers

    private static Object unwrap(Object o) {
        if (o instanceof PatternNode) {
            PatternNode n = (PatternNode) o;
            if (n.value == null) {
                throw new PatternException("'" + n.name + "' is a " + n.typeName + " and has no single value", 0);
            }
            return n.value;
        }
        return o;
    }

    private static long num(Object o) {
        o = unwrap(o);
        if (o instanceof Long) {
            return (Long) o;
        }
        if (o instanceof Double) {
            return (long) (double) (Double) o;
        }
        throw new PatternException("expected a number but got a string", 0);
    }

    private static double dbl(Object o) {
        o = unwrap(o);
        if (o instanceof Long) {
            return (Long) o;
        }
        if (o instanceof Double) {
            return (Double) o;
        }
        throw new PatternException("expected a number but got a string", 0);
    }

    private static boolean truthy(Object o) {
        o = unwrap(o);
        if (o instanceof Long) {
            return (Long) o != 0;
        }
        if (o instanceof Double) {
            return (Double) o != 0;
        }
        return !((String) o).isEmpty();
    }

    private static Object binary(String op, Object a, Object b) {
        a = unwrap(a);
        b = unwrap(b);
        if (op.equals("&&")) {
            return truthy(a) && truthy(b) ? 1L : 0L;
        }
        if (op.equals("||")) {
            return truthy(a) || truthy(b) ? 1L : 0L;
        }
        if (a instanceof String || b instanceof String) {
            String x = String.valueOf(a), y = String.valueOf(b);
            if (op.equals("+")) {
                return x + y;
            }
            if (op.equals("==")) {
                return x.equals(y) ? 1L : 0L;
            }
            if (op.equals("!=")) {
                return !x.equals(y) ? 1L : 0L;
            }
            throw new PatternException("operator " + op + " not valid for strings", 0);
        }
        if (a instanceof Double || b instanceof Double) {
            double x = dbl(a), y = dbl(b);
            switch (op) {
                case "+": return x + y;
                case "-": return x - y;
                case "*": return x * y;
                case "/": return x / y;
                case "==": return x == y ? 1L : 0L;
                case "!=": return x != y ? 1L : 0L;
                case "<": return x < y ? 1L : 0L;
                case ">": return x > y ? 1L : 0L;
                case "<=": return x <= y ? 1L : 0L;
                case ">=": return x >= y ? 1L : 0L;
                default: throw new PatternException("operator " + op + " not valid for floats", 0);
            }
        }
        long x = (Long) a, y = (Long) b;
        switch (op) {
            case "+": return x + y;
            case "-": return x - y;
            case "*": return x * y;
            case "/":
                if (y == 0) throw new PatternException("division by zero", 0);
                return x / y;
            case "%":
                if (y == 0) throw new PatternException("division by zero", 0);
                return x % y;
            case "<<": return x << y;
            case ">>": return x >> y;
            case "&": return x & y;
            case "|": return x | y;
            case "^": return x ^ y;
            case "==": return x == y ? 1L : 0L;
            case "!=": return x != y ? 1L : 0L;
            case "<": return x < y ? 1L : 0L;
            case ">": return x > y ? 1L : 0L;
            case "<=": return x <= y ? 1L : 0L;
            case ">=": return x >= y ? 1L : 0L;
            default: throw new PatternException("unknown operator " + op, 0);
        }
    }

    // ------------------------------------------------------------------ type definitions

    private static void applyAttrs(PatternNode n, Map<String, String> attrs) {
        if (attrs == null) {
            return;
        }
        String c = attrs.get("color");
        if (c != null) {
            try {
                n.color = Integer.parseInt(c.replace("#", "").replace("0x", ""), 16) & 0xFFFFFF;
            } catch (NumberFormatException ex) {
                // ignore bad colour
            }
        }
        if (attrs.containsKey("comment")) {
            n.comment = attrs.get("comment");
        }
        if (attrs.containsKey("hidden")) {
            n.hidden = true;
        }
    }

    private static final class StructDef extends TypeDef {
        final String name;
        final boolean union;
        final List<Stmt> body;
        final Map<String, String> attrs;

        StructDef(String name, boolean union, List<Stmt> body, Map<String, String> attrs) {
            this.name = name;
            this.union = union;
            this.body = body;
            this.attrs = attrs;
        }

        @Override
        PatternNode make(Interp in, String varName, Scope up, PatternNode parent) {
            PatternNode node = in.newNode(varName, name, parent);
            applyAttrs(node, attrs);
            Scope sc = new Scope(node, up);
            long start = in.cursor, max = start;
            in.depth++;
            try {
                for (Stmt s : body) {
                    if (union) {
                        in.cursor = start;
                    }
                    s.exec(sc, in);
                    max = Math.max(max, in.cursor);
                }
            } finally {
                in.depth--;
                node.size = Math.max(max, in.cursor) - start;
            }
            in.cursor = start + node.size;
            node.display = "{ " + node.children.size() + " members }";
            return node;
        }
    }

    private static final class EnumDef extends TypeDef {
        final String name;
        final TypeRef under;
        final Map<String, Long> values = new LinkedHashMap<String, Long>();

        EnumDef(String name, TypeRef under) {
            this.name = name;
            this.under = under;
        }

        @Override
        PatternNode make(Interp in, String varName, Scope up, PatternNode parent) {
            long start = in.cursor;
            PatternNode n = in.place(under, varName, up, null);
            long v = n.value instanceof Long ? (Long) n.value : 0;
            String found = null;
            for (Map.Entry<String, Long> e : values.entrySet()) {
                if (e.getValue() == v) {
                    found = e.getKey();
                    break;
                }
            }
            in.cursor = start;
            PatternNode r = in.newNode(varName, name, parent);
            in.cursor = start + n.size;
            r.size = n.size;
            r.value = n.value;
            r.display = (found != null ? name + "::" + found : name + "::???") + " (0x"
                    + Long.toHexString(v).toUpperCase() + ")";
            return r;
        }
    }

    private static final class BitfieldDef extends TypeDef {
        final String name;
        final List<String> fieldNames = new ArrayList<String>();
        final List<Expr> fieldBits = new ArrayList<Expr>();

        BitfieldDef(String name) {
            this.name = name;
        }

        @Override
        PatternNode make(Interp in, String varName, Scope up, PatternNode parent) {
            PatternNode node = in.newNode(varName, name, parent);
            Scope sc = new Scope(node, up);
            int[] widths = new int[fieldBits.size()];
            int total = 0;
            for (int i = 0; i < widths.length; i++) {
                widths[i] = (int) num(fieldBits.get(i).eval(sc, in));
                total += widths[i];
            }
            if (total > 64 || total <= 0) {
                throw new PatternException("bitfield " + name + " must be 1..64 bits, is " + total, 0);
            }
            int bytes = (total + 7) / 8;
            long raw = in.readUInt(in.cursor, bytes, false);
            int bit = 0;
            for (int i = 0; i < widths.length; i++) {
                if (!fieldNames.get(i).equals("padding")) {
                    PatternNode f = in.newNode(fieldNames.get(i), "bits", node);
                    f.offset = node.offset + bit / 8;
                    f.size = Math.max(1, (bit % 8 + widths[i] + 7) / 8);
                    f.bitOffset = bit % 8;
                    f.bitSize = widths[i];
                    long mask = widths[i] >= 64 ? -1L : (1L << widths[i]) - 1;
                    long v = (raw >>> bit) & mask;
                    f.value = v;
                    f.display = v + " (" + widths[i] + " bit" + (widths[i] == 1 ? "" : "s") + ")";
                }
                bit += widths[i];
            }
            node.size = bytes;
            node.display = "{ bitfield, " + total + " bits }";
            in.cursor += bytes;
            return node;
        }
    }

    private static final class AliasDef extends TypeDef {
        final TypeRef target;

        AliasDef(TypeRef target) {
            this.target = target;
        }

        @Override
        PatternNode make(Interp in, String name, Scope up, PatternNode parent) {
            return in.place(target, name, up, parent);
        }
    }

    // ------------------------------------------------------------------ parser

    private static final class Parser {
        final List<Tok> toks;
        int p;
        final Program prog = new Program();

        Parser(List<Tok> toks) {
            this.toks = toks;
        }

        Tok peek() {
            return toks.get(p);
        }

        Tok peek(int k) {
            return toks.get(Math.min(p + k, toks.size() - 1));
        }

        Tok next() {
            Tok t = toks.get(p);
            if (t.k != K.EOF) {
                p++;
            }
            return t;
        }

        boolean isOp(String s) {
            Tok t = peek();
            return t.k == K.OP && t.s.equals(s);
        }

        boolean isId(String s) {
            Tok t = peek();
            return t.k == K.ID && t.s.equals(s);
        }

        PatternException err(String msg) {
            return new PatternException(msg, peek().line);
        }

        void expectOp(String s) {
            if (!isOp(s)) {
                throw err("expected '" + s + "' but found '" + peek().s + "'");
            }
            next();
        }

        String expectId() {
            if (peek().k != K.ID) {
                throw err("expected a name but found '" + peek().s + "'");
            }
            return next().s;
        }

        Program parseProgram() {
            while (peek().k != K.EOF) {
                prog.stmts.add(parseStmt());
            }
            return prog;
        }

        List<Stmt> parseBlock() {
            expectOp("{");
            List<Stmt> list = new ArrayList<Stmt>();
            while (!isOp("}")) {
                if (peek().k == K.EOF) {
                    throw err("missing '}'");
                }
                list.add(parseStmt());
            }
            next();
            return list;
        }

        List<Stmt> parseBodyOrStmt() {
            if (isOp("{")) {
                return parseBlock();
            }
            List<Stmt> l = new ArrayList<Stmt>();
            l.add(parseStmt());
            return l;
        }

        Stmt parseStmt() {
            Tok t = peek();
            if (t.k == K.PRAGMA) {
                next();
                String[] w = t.s.split("\\s+");
                if (w.length >= 2 && w[0].equals("endian")) {
                    prog.bigEndian = w[1].equals("big");
                }
                return NOP;
            }
            if (isOp(";")) {
                next();
                return NOP;
            }
            if (t.k != K.ID) {
                throw err("unexpected '" + t.s + "'");
            }
            switch (t.s) {
                case "struct":
                case "union":
                    parseStruct();
                    return NOP;
                case "enum":
                    parseEnum();
                    return NOP;
                case "bitfield":
                    parseBitfield();
                    return NOP;
                case "using":
                    parseUsing();
                    return NOP;
                case "if":
                    return parseIf();
                case "fn":
                    parseFunction();
                    return NOP;
                case "import":
                    while (!isOp(";") && peek().k != K.EOF) {
                        next();
                    }
                    expectOp(";");
                    return NOP;
                case "return": {
                    next();
                    final Expr value = isOp(";") ? null : parseExpr();
                    expectOp(";");
                    return new Stmt() {
                        @Override
                        public void exec(Scope sc, Interp in) {
                            throw new ReturnSignal(value == null ? null : value.eval(sc, in));
                        }
                    };
                }
                case "namespace": {
                    next();
                    expectId();
                    final List<Stmt> body = parseBlock();
                    return new Stmt() {
                        @Override
                        public void exec(Scope sc, Interp in) {
                            for (Stmt s : body) {
                                s.exec(sc, in);
                            }
                        }
                    };
                }
                case "padding":
                    if (peek(1).k == K.OP && peek(1).s.equals("[")) {
                        return parsePadding();
                    }
                    break;
                default:
                    break;
            }
            Tok n = peek(1);
            if (n.k == K.OP && (n.s.equals("=") || n.s.equals("+=") || n.s.equals("-="))) {
                return parseAssign();
            }
            return parseDecl();
        }

        void parseFunction() {
            next(); // fn
            final String name = expectId();
            expectOp("(");
            List<String> params = new ArrayList<String>();
            while (!isOp(")")) {
                String lastName = null;
                while (!isOp(",") && !isOp(")")) {
                    Tok t = next();
                    if (t.k == K.EOF) {
                        throw err("missing ')' in function " + name);
                    }
                    if (t.k == K.ID) {
                        lastName = t.s;
                    }
                }
                if (lastName != null) {
                    params.add(lastName);
                }
                if (isOp(",")) {
                    next();
                }
            }
            next();
            prog.functions.put(name, new FuncDef(params, parseBlock()));
        }

        TypeRef parseTypeRef() {
            int endian = 0;
            if (isId("be") && peek(1).k == K.ID) {
                endian = 2;
                next();
            } else if (isId("le") && peek(1).k == K.ID) {
                endian = 1;
                next();
            }
            String name = expectId();
            String arg = null;
            if (isOp("<") && peek(1).k == K.STR && peek(2).k == K.OP && peek(2).s.equals(">")) {
                next();
                arg = (String) next().v;
                next();
            } else if (isOp("<")) {
                throw err("templates are not supported (except type::Magic<\"...\">)");
            }
            return new TypeRef(name, endian, arg);
        }

        Map<String, String> parseAttrs() {
            Map<String, String> m = new HashMap<String, String>();
            while (isOp("[") && peek(1).k == K.OP && peek(1).s.equals("[")) {
                next();
                next();
                do {
                    if (isOp(",")) {
                        next();
                    }
                    String key = expectId();
                    String arg = "";
                    if (isOp("(")) {
                        next();
                        if (!isOp(")")) {
                            arg = next().s;
                            while (!isOp(")") && peek().k != K.EOF) {
                                next();
                            }
                        }
                        expectOp(")");
                    }
                    m.put(key, arg);
                } while (isOp(","));
                expectOp("]");
                expectOp("]");
            }
            return m;
        }

        void parseStruct() {
            boolean union = next().s.equals("union");
            String name = expectId();
            if (isOp("<")) {
                throw err("templates are not supported");
            }
            if (isOp(":")) {
                throw err("struct inheritance is not supported");
            }
            List<Stmt> body = parseBlock();
            Map<String, String> attrs = parseAttrs();
            expectOp(";");
            prog.types.put(name, new StructDef(name, union, body, attrs));
        }

        void parseEnum() {
            next();
            String name = expectId();
            TypeRef under = new TypeRef("u32", 0);
            if (isOp(":")) {
                next();
                under = parseTypeRef();
            }
            EnumDef def = new EnumDef(name, under);
            Scope sc = new Scope(null, null);
            Interp dummy = new Interp(null, prog.types, new ArrayList<String>());
            expectOp("{");
            long nextVal = 0;
            while (!isOp("}")) {
                String en = expectId();
                if (isOp("=")) {
                    next();
                    nextVal = num(parseExpr().eval(sc, dummy));
                }
                def.values.put(en, nextVal);
                sc.vars.put(en, nextVal);
                nextVal++;
                if (isOp(",")) {
                    next();
                } else if (!isOp("}")) {
                    throw err("expected ',' or '}' in enum");
                }
            }
            next();
            expectOp(";");
            prog.types.put(name, def);
        }

        void parseBitfield() {
            next();
            BitfieldDef def = new BitfieldDef(expectId());
            expectOp("{");
            while (!isOp("}")) {
                if (peek(1).k == K.ID) {
                    next(); // optional type before the name, width decides the size
                }
                String fn = expectId();
                expectOp(":");
                def.fieldNames.add(fn);
                def.fieldBits.add(parseExpr());
                expectOp(";");
            }
            next();
            expectOp(";");
            prog.types.put(def.name, def);
        }

        void parseUsing() {
            next();
            String name = expectId();
            expectOp("=");
            TypeRef target = parseTypeRef();
            expectOp(";");
            prog.types.put(name, new AliasDef(target));
        }

        Stmt parseIf() {
            next();
            expectOp("(");
            final Expr cond = parseExpr();
            expectOp(")");
            final List<Stmt> a = parseBodyOrStmt();
            List<Stmt> bb = null;
            if (isId("else")) {
                next();
                if (isId("if")) {
                    bb = new ArrayList<Stmt>();
                    bb.add(parseIf());
                } else {
                    bb = parseBodyOrStmt();
                }
            }
            final List<Stmt> b = bb;
            return new Stmt() {
                @Override
                public void exec(Scope sc, Interp in) {
                    List<Stmt> run = truthy(cond.eval(sc, in)) ? a : b;
                    if (run != null) {
                        for (Stmt s : run) {
                            s.exec(sc, in);
                        }
                    }
                }
            };
        }

        Stmt parsePadding() {
            final int line = next().line;
            expectOp("[");
            final Expr size = parseExpr();
            expectOp("]");
            expectOp(";");
            return new Stmt() {
                @Override
                public void exec(Scope sc, Interp in) {
                    long n = num(size.eval(sc, in));
                    if (n < 0 || in.cursor + n > in.src.length()) {
                        throw new PatternException("padding runs past the end of the data", line);
                    }
                    in.cursor += n;
                }
            };
        }

        Stmt parseAssign() {
            final int line = peek().line;
            final String name = next().s;
            final String op = next().s;
            final Expr e = parseExpr();
            expectOp(";");
            return new Stmt() {
                @Override
                public void exec(Scope sc, Interp in) {
                    try {
                        Object v = unwrap(e.eval(sc, in));
                        if (name.equals("$")) {
                            long cur = op.equals("=") ? num(v) : op.equals("+=") ? in.cursor + num(v) : in.cursor - num(v);
                            in.cursor = cur;
                            return;
                        }
                        if (!op.equals("=")) {
                            Object old = sc.lookup(name);
                            if (old == Scope.MISSING) {
                                throw new PatternException("unknown variable '" + name + "'", 0);
                            }
                            v = binary(op.substring(0, 1), old, v);
                        }
                        if (!sc.assign(name, v)) {
                            throw new PatternException("'" + name + "' is not a local variable", 0);
                        }
                    } catch (PatternException ex) {
                        throw ex.line == 0 ? new PatternException(ex.getMessage(), line) : ex;
                    }
                }
            };
        }

        Stmt parseDecl() {
            final int line = peek().line;
            final TypeRef type = parseTypeRef();
            final List<String> names = new ArrayList<String>();
            final List<List<Expr>> dimsList = new ArrayList<List<Expr>>();
            final List<Expr> inits = new ArrayList<Expr>();
            do {
                if (isOp(",")) {
                    next();
                }
                names.add(expectId());
                List<Expr> dims = new ArrayList<Expr>();
                while (isOp("[") && !(peek(1).k == K.OP && peek(1).s.equals("["))) {
                    next();
                    if (isOp("]")) {
                        dims.add(new UntilNullDim());
                    } else if (isId("while")) {
                        next();
                        expectOp("(");
                        Expr cond = parseExpr();
                        expectOp(")");
                        dims.add(new WhileDim(cond));
                    } else {
                        dims.add(parseExpr());
                    }
                    expectOp("]");
                }
                dimsList.add(dims);
                if (isOp("=")) {
                    next();
                    inits.add(parseExpr());
                } else {
                    inits.add(null);
                }
            } while (isOp(","));
            Expr place = null;
            final Map<String, String> attrs = new HashMap<String, String>();
            while (true) {
                if (isOp("@")) {
                    next();
                    place = parseExpr();
                } else if (isOp("[") && peek(1).k == K.OP && peek(1).s.equals("[")) {
                    attrs.putAll(parseAttrs());
                } else {
                    break;
                }
            }
            expectOp(";");
            final Expr fPlace = place;
            return new Stmt() {
                @Override
                public void exec(Scope sc, Interp in) {
                    try {
                        long save = in.cursor;
                        boolean moved = false;
                        for (int k = 0; k < names.size(); k++) {
                            String name = names.get(k);
                            if (inits.get(k) != null) {
                                sc.vars.put(name, unwrap(inits.get(k).eval(sc, in)));
                                continue;
                            }
                            if (sc.node == null) {
                                throw new PatternException("cannot place '" + name + "' inside a function", 0);
                            }
                            if (fPlace != null && !moved) {
                                in.cursor = num(fPlace.eval(sc, in));
                                moved = true;
                            }
                            List<Expr> dims = dimsList.get(k);
                            PatternNode n;
                            if (dims.isEmpty()) {
                                n = in.place(type, name, sc, sc.node);
                            } else {
                                n = in.placeArray(type, name, sc, sc.node, dims, 0);
                            }
                            applyAttrs(n, attrs);
                            if (attrs.containsKey("name") && !attrs.get("name").isEmpty()) {
                                PatternNode renamed = new PatternNode(attrs.get("name"), n.typeName);
                                renamed.offset = n.offset;
                                renamed.size = n.size;
                                renamed.value = n.value;
                                renamed.display = n.display;
                                renamed.comment = n.comment;
                                renamed.color = n.color;
                                renamed.hidden = n.hidden;
                                renamed.parent = n.parent;
                                renamed.children.addAll(n.children);
                                for (PatternNode c : renamed.children) {
                                    c.parent = renamed;
                                }
                                int at = sc.node.children.lastIndexOf(n);
                                if (at >= 0) {
                                    sc.node.children.set(at, renamed);
                                }
                                n = renamed;
                            }
                        }
                        if (moved && in.depth > 0) {
                            in.cursor = save;
                        }
                    } catch (PatternException ex) {
                        throw ex.line == 0 ? new PatternException(ex.getMessage(), line) : ex;
                    }
                }
            };
        }

        // ---- expressions

        static final String[][] LEVELS = {{"||"}, {"&&"}, {"|"}, {"^"}, {"&"}, {"==", "!="},
            {"<", ">", "<=", ">="}, {"<<", ">>"}, {"+", "-"}, {"*", "/", "%"}};

        Expr parseExpr() {
            Expr c = parseBinary(0);
            if (isOp("?")) {
                next();
                final Expr a = parseExpr();
                expectOp(":");
                final Expr b = parseExpr();
                final Expr cond = c;
                return new Expr() {
                    @Override
                    public Object eval(Scope sc, Interp in) {
                        return truthy(cond.eval(sc, in)) ? a.eval(sc, in) : b.eval(sc, in);
                    }
                };
            }
            return c;
        }

        Expr parseBinary(int level) {
            if (level >= LEVELS.length) {
                return parseUnary();
            }
            Expr left = parseBinary(level + 1);
            while (true) {
                String found = null;
                if (peek().k == K.OP) {
                    for (String op : LEVELS[level]) {
                        if (peek().s.equals(op)) {
                            found = op;
                        }
                    }
                }
                if (found == null) {
                    return left;
                }
                next();
                final String op = found;
                final Expr l = left, r = parseBinary(level + 1);
                left = new Expr() {
                    @Override
                    public Object eval(Scope sc, Interp in) {
                        return binary(op, l.eval(sc, in), r.eval(sc, in));
                    }
                };
            }
        }

        Expr parseUnary() {
            if (peek().k == K.OP) {
                final String op = peek().s;
                if (op.equals("-") || op.equals("!") || op.equals("~") || op.equals("+")) {
                    next();
                    final Expr e = parseUnary();
                    return new Expr() {
                        @Override
                        public Object eval(Scope sc, Interp in) {
                            Object v = unwrap(e.eval(sc, in));
                            switch (op) {
                                case "-": return v instanceof Double ? (Object) (-(Double) v) : (Object) (-num(v));
                                case "!": return truthy(v) ? 0L : 1L;
                                case "~": return ~num(v);
                                default: return v;
                            }
                        }
                    };
                }
            }
            return parsePostfix();
        }

        Expr parsePostfix() {
            Expr e = parsePrimary();
            while (true) {
                if (isOp(".") && peek(1).k == K.ID) {
                    next();
                    final String member = next().s;
                    final Expr base = e;
                    e = new Expr() {
                        @Override
                        public Object eval(Scope sc, Interp in) {
                            Object b = base.eval(sc, in);
                            if (!(b instanceof PatternNode)) {
                                throw new PatternException("cannot access ." + member + " on a plain value", 0);
                            }
                            PatternNode n = (PatternNode) b;
                            if (member.equals("parent")) {
                                if (n.parent == null) throw new PatternException("no parent", 0);
                                return n.parent;
                            }
                            for (int i = n.children.size() - 1; i >= 0; i--) {
                                if (n.children.get(i).name.equals(member)) {
                                    return n.children.get(i);
                                }
                            }
                            throw new PatternException("'" + n.name + "' has no member '" + member + "'", 0);
                        }
                    };
                } else if (isOp("[") && !(peek(1).k == K.OP && peek(1).s.equals("["))) {
                    next();
                    final Expr idx = parseExpr();
                    expectOp("]");
                    final Expr base = e;
                    e = new Expr() {
                        @Override
                        public Object eval(Scope sc, Interp in) {
                            Object b = base.eval(sc, in);
                            long i = num(idx.eval(sc, in));
                            if (!(b instanceof PatternNode) || i < 0 || i >= ((PatternNode) b).children.size()) {
                                throw new PatternException("index " + i + " out of range", 0);
                            }
                            return ((PatternNode) b).children.get((int) i);
                        }
                    };
                } else {
                    return e;
                }
            }
        }

        Expr parsePrimary() {
            final Tok t = next();
            switch (t.k) {
                case NUM:
                case CHR:
                case STR: {
                    final Object v = t.v;
                    return new Expr() {
                        @Override
                        public Object eval(Scope sc, Interp in) {
                            return v;
                        }
                    };
                }
                case OP:
                    if (t.s.equals("(")) {
                        Expr e = parseExpr();
                        expectOp(")");
                        return e;
                    }
                    break;
                case ID:
                    return parseIdentifier(t);
                default:
                    break;
            }
            throw new PatternException("unexpected '" + t.s + "' in expression", t.line);
        }

        Expr parseIdentifier(final Tok t) {
            final String id = t.s;
            if (id.equals("true") || id.equals("false")) {
                final Object v = id.equals("true") ? 1L : 0L;
                return new Expr() {
                    @Override
                    public Object eval(Scope sc, Interp in) {
                        return v;
                    }
                };
            }
            if (id.equals("$")) {
                return new Expr() {
                    @Override
                    public Object eval(Scope sc, Interp in) {
                        return in.cursor;
                    }
                };
            }
            if ((id.equals("sizeof") || id.equals("addressof")) && isOp("(")) {
                next();
                final Expr arg = parseExpr();
                final String typeName = lastIdent;
                expectOp(")");
                final boolean isSize = id.equals("sizeof");
                return new Expr() {
                    @Override
                    public Object eval(Scope sc, Interp in) {
                        Object v;
                        try {
                            v = arg.eval(sc, in);
                        } catch (PatternException ex) {
                            if (isSize && typeName != null) {
                                return in.sizeOfType(typeName);
                            }
                            throw ex;
                        }
                        if (!(v instanceof PatternNode)) {
                            throw new PatternException(id + " needs a variable or type", 0);
                        }
                        return isSize ? ((PatternNode) v).size : ((PatternNode) v).offset;
                    }
                };
            }
            if (isOp("(")) {
                next();
                final List<Expr> args = new ArrayList<Expr>();
                while (!isOp(")")) {
                    args.add(parseExpr());
                    if (isOp(",")) {
                        next();
                    }
                }
                next();
                return new Expr() {
                    @Override
                    public Object eval(Scope sc, Interp in) {
                        return call(id, args, sc, in);
                    }
                };
            }
            lastIdent = id;
            return new Expr() {
                @Override
                public Object eval(Scope sc, Interp in) {
                    if (id.equals("this")) {
                        return sc.node;
                    }
                    if (id.equals("parent")) {
                        if (sc.node == null || sc.node.parent == null) {
                            throw new PatternException("no parent here", 0);
                        }
                        return sc.node.parent;
                    }
                    Object v = sc.lookup(id);
                    if (v != Scope.MISSING) {
                        return v;
                    }
                    int sep = id.lastIndexOf("::");
                    if (sep > 0) {
                        TypeDef td = in.types.get(id.substring(0, sep));
                        if (td instanceof EnumDef) {
                            Long ev = ((EnumDef) td).values.get(id.substring(sep + 2));
                            if (ev != null) {
                                return ev;
                            }
                        }
                    }
                    throw new PatternException("unknown name '" + id + "'", 0);
                }
            };
        }

        /** Name of the most recently parsed bare identifier, used by sizeof(Type). */
        String lastIdent;

        static String fmt(Object o) {
            if (o instanceof PatternNode) {
                PatternNode n = (PatternNode) o;
                return n.display != null ? n.display : String.valueOf(n.value);
            }
            return String.valueOf(o);
        }

        static String format(List<Expr> args, Scope sc, Interp in) {
            String f = String.valueOf(unwrap(args.get(0).eval(sc, in)));
            for (int i = 1; i < args.size(); i++) {
                f = f.replaceFirst("\\{\\}", java.util.regex.Matcher.quoteReplacement(fmt(args.get(i).eval(sc, in))));
            }
            return f;
        }

        static Object call(String fn, List<Expr> args, Scope sc, Interp in) {
            FuncDef user = in.functions.get(fn);
            if (user != null) {
                if (args.size() != user.params.size()) {
                    throw new PatternException("function '" + fn + "' expects " + user.params.size() + " argument(s)", 0);
                }
                Scope fs = new Scope(null, null);
                for (int i = 0; i < args.size(); i++) {
                    fs.vars.put(user.params.get(i), args.get(i).eval(sc, in));
                }
                if (++in.callDepth > 200) {
                    in.callDepth--;
                    throw new PatternException("function calls nested too deeply", 0);
                }
                try {
                    for (Stmt st : user.body) {
                        st.exec(fs, in);
                    }
                } catch (ReturnSignal r) {
                    return r.value == null ? (Object) 0L : r.value;
                } finally {
                    in.callDepth--;
                }
                return 0L;
            }
            switch (fn) {
                case "std::mem::find_sequence_in_range": {
                    long occurrence = num(args.get(0).eval(sc, in));
                    long start = num(args.get(1).eval(sc, in));
                    long end = num(args.get(2).eval(sc, in));
                    int n = args.size() - 3;
                    if (n <= 0) {
                        throw new PatternException("find_sequence_in_range needs at least one byte", 0);
                    }
                    int[] seq = new int[n];
                    for (int i = 0; i < n; i++) {
                        seq[i] = (int) num(args.get(3 + i).eval(sc, in)) & 0xFF;
                    }
                    long limit = Math.min(end, in.src.length()) - n;
                    long found = 0;
                    for (long p = Math.max(0, start); p <= limit; p++) {
                        boolean match = true;
                        for (int i = 0; i < n && match; i++) {
                            match = in.src.get(p + i) == seq[i];
                        }
                        if (match) {
                            if (found == occurrence) {
                                return p;
                            }
                            found++;
                        }
                    }
                    return -1L;
                }
                case "std::format":
                    return format(args, sc, in);
                case "std::mem::size":
                    return in.src.length();
                case "std::mem::eof":
                    return in.cursor >= in.src.length() ? 1L : 0L;
                case "std::mem::read_unsigned":
                case "std::mem::read_signed": {
                    long addr = num(args.get(0).eval(sc, in));
                    int size = (int) num(args.get(1).eval(sc, in));
                    long v = in.readUInt(addr, size, args.size() > 2 ? "big".equals(unwrap(args.get(2).eval(sc, in))) : in.big);
                    if (fn.endsWith("signed") && !fn.endsWith("unsigned") && size < 8) {
                        int sh = 64 - 8 * size;
                        v = (v << sh) >> sh;
                    }
                    return v;
                }
                case "std::print": {
                    in.log.add(format(args, sc, in));
                    return 0L;
                }
                default:
                    throw new PatternException("function '" + fn + "' is not supported", 0);
            }
        }
    }
}
