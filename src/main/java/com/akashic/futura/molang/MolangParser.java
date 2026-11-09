/*
 * Adapted from Yes Steve Model, src/main/java/com/elfmcys/ysm/expression/frontend/MolangSourceParser.java and
 * src/main/java/com/elfmcys/ysm/expression/frontend/MolangNames.java, Apache-2.0. Upstream lowers to a portable
 * program for its compiler; this one builds interpreter nodes directly.
 */
package com.akashic.futura.molang;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lexer and Pratt parser of the YSM Molang dialect.
 * <p>
 * Precedence, lowest first: {@code =} (right), {@code ->} (right), {@code ?:} (right; {@code c ? a} without
 * {@code :} gives a missing value when false), {@code ??} (right), {@code ||}, {@code &&}, {@code == !=},
 * {@code < <= > >=}, {@code + -}, {@code * /}, prefix {@code - + !}. Identifiers and keywords are case-insensitive;
 * strings use single quotes and have no escapes; {@code //} starts a comment.
 */
final class MolangParser {

    private static final int EOF = 0, IDENTIFIER = 1, NUMBER = 2, STRING = 3, TRUE = 4, FALSE = 5, BREAK = 6,
        CONTINUE = 7, RETURN = 8, DOT = 9, BANG = 10, AND_AND = 11, OR_OR = 12, LT = 13, LTE = 14, GT = 15, GTE = 16,
        EQ = 17, EQ_EQ = 18, BANG_EQ = 19, STAR = 20, SLASH = 21, PLUS = 22, MINUS = 23, LPAREN = 24, RPAREN = 25,
        LBRACE = 26, RBRACE = 27, QUESTION_QUESTION = 28, QUESTION = 29, COLON = 30, ARROW = 31, LBRACKET = 32,
        RBRACKET = 33, COMMA = 34, SEMICOLON = 35;

    private static final int PREC_CONDITIONAL = 3;
    private static final int PREC_PREFIX = 11;
    private static final int MAX_DEPTH = 256;
    private static final int MAX_IDENTIFIER = 128;
    private static final int MAX_STRING_BYTES = 65_535;
    private static final String ROAMING_SLOT_PREFIX = "__ysm_roaming_";

    private final String source;
    private int offset;
    private int kind;
    private String text;
    private int tokenStart;

    private int depth;
    private int loopDepth;
    private final Map<String, Integer> temps = new HashMap<>();
    private final List<Integer> scratchSizes = new ArrayList<>();

    private MolangParser(String source) {
        this.source = source;
    }

    static CompiledProgram compile(String source) throws MolangSyntaxException {
        MolangParser parser = new MolangParser(source);
        parser.advance();
        Node[] statements = parser.parseProgram();
        int[] scratch = new int[parser.scratchSizes.size()];
        for (int i = 0; i < scratch.length; i++) {
            scratch[i] = parser.scratchSizes.get(i);
        }
        return new CompiledProgram(source, statements, parser.temps.size(), scratch);
    }

    // Grammar

    private Node[] parseProgram() throws MolangSyntaxException {
        List<Node> roots = new ArrayList<>();
        while (kind != EOF) {
            if (accept(SEMICOLON)) {
                continue;
            }
            roots.add(parseExpression(0));
            if (kind != EOF && !accept(SEMICOLON)) {
                throw fail("expected ';' or end of source");
            }
        }
        if (roots.isEmpty()) {
            roots.add(new Nodes.Const(null));
        }
        return roots.toArray(new Node[0]);
    }

    private Node parseExpression(int minimumPrecedence) throws MolangSyntaxException {
        if (++depth > MAX_DEPTH) {
            throw fail("source nesting exceeds " + MAX_DEPTH);
        }
        try {
            Node left = parsePrefix();
            while (true) {
                if (kind == QUESTION && PREC_CONDITIONAL >= minimumPrecedence) {
                    advance();
                    Node ifTrue = parseExpression(0);
                    Node ifFalse = accept(COLON) ? parseExpression(PREC_CONDITIONAL) : new Nodes.Const(null);
                    left = Nodes.fold(new Nodes.Conditional(left, ifTrue, ifFalse));
                    continue;
                }
                int operator = kind;
                int precedence = precedence(operator);
                if (precedence < 0 || precedence < minimumPrecedence) {
                    return left;
                }
                int assignOffset = tokenStart;
                advance();
                boolean rightAssociative = operator == EQ || operator == ARROW || operator == QUESTION_QUESTION;
                Node right = parseExpression(rightAssociative ? precedence : precedence + 1);
                left = binary(operator, left, right, assignOffset);
            }
        } finally {
            depth--;
        }
    }

    private static int precedence(int operator) {
        switch (operator) {
            case EQ:
                return 1;
            case ARROW:
                return 2;
            case QUESTION_QUESTION:
                return 4;
            case OR_OR:
                return 5;
            case AND_AND:
                return 6;
            case EQ_EQ:
            case BANG_EQ:
                return 7;
            case LT:
            case LTE:
            case GT:
            case GTE:
                return 8;
            case PLUS:
            case MINUS:
                return 9;
            case STAR:
            case SLASH:
                return 10;
            default:
                return -1;
        }
    }

    private Node binary(int operator, Node left, Node right, int operatorOffset) throws MolangSyntaxException {
        switch (operator) {
            case EQ:
                return assignment(left, right, operatorOffset);
            case ARROW:
                return new Nodes.Arrow(left, right);
            case QUESTION_QUESTION:
                return Nodes.fold(new Nodes.Coalesce(left, right));
            case OR_OR:
                return Nodes.fold(new Nodes.Or(left, right));
            case AND_AND:
                return Nodes.fold(new Nodes.And(left, right));
            case EQ_EQ:
                return Nodes.fold(new Nodes.Equals(left, right, false));
            case BANG_EQ:
                return Nodes.fold(new Nodes.Equals(left, right, true));
            case LT:
                return Nodes.fold(new Nodes.Binary(Nodes.LT, left, right));
            case LTE:
                return Nodes.fold(new Nodes.Binary(Nodes.LTE, left, right));
            case GT:
                return Nodes.fold(new Nodes.Binary(Nodes.GT, left, right));
            case GTE:
                return Nodes.fold(new Nodes.Binary(Nodes.GTE, left, right));
            case PLUS:
                return Nodes.fold(new Nodes.Binary(Nodes.ADD, left, right));
            case MINUS:
                return Nodes.fold(new Nodes.Binary(Nodes.SUB, left, right));
            case STAR:
                return Nodes.fold(new Nodes.Binary(Nodes.MUL, left, right));
            default:
                return Nodes.fold(new Nodes.Binary(Nodes.DIV, left, right));
        }
    }

    private Node parsePrefix() throws MolangSyntaxException {
        switch (kind) {
            case NUMBER: {
                String literal = text;
                int at = tokenStart;
                advance();
                float value;
                try {
                    value = Float.parseFloat(literal);
                } catch (NumberFormatException e) {
                    throw new MolangSyntaxException("invalid numeric literal", at);
                }
                if (Float.isInfinite(value) || Float.isNaN(value)) {
                    throw new MolangSyntaxException("non-finite numeric literal", at);
                }
                return new Nodes.Const(MolangValue.of((double) value));
            }
            case STRING: {
                String value = text;
                advance();
                return new Nodes.Const(MolangValue.of(value));
            }
            case TRUE:
                advance();
                return new Nodes.Const(MolangValue.ONE);
            case FALSE:
                advance();
                return new Nodes.Const(MolangValue.ZERO);
            case IDENTIFIER:
                return parseIdentifier();
            case LPAREN: {
                advance();
                Node expression = parseExpression(0);
                require(RPAREN, "expected ')'");
                advance();
                return parsePostfix(expression);
            }
            case LBRACE:
                return parsePostfix(parseBlock());
            case PLUS:
                advance();
                return Nodes.fold(new Nodes.Positive(parseExpression(PREC_PREFIX)));
            case MINUS:
                advance();
                return Nodes.fold(new Nodes.Negate(parseExpression(PREC_PREFIX)));
            case BANG:
                advance();
                return Nodes.fold(new Nodes.Not(parseExpression(PREC_PREFIX)));
            case RETURN:
                return parseReturn();
            case BREAK:
            case CONTINUE: {
                if (loopDepth == 0) {
                    throw fail((kind == BREAK ? "break" : "continue") + " outside of a loop");
                }
                int jump = kind == BREAK ? Frame.BREAK : Frame.CONTINUE;
                advance();
                return new Nodes.Jump(jump);
            }
            default:
                throw fail("expected expression");
        }
    }

    private Node parseIdentifier() throws MolangSyntaxException {
        List<String> parts = new ArrayList<>();
        parts.add(identifier());
        advance();
        while (accept(DOT)) {
            require(IDENTIFIER, "expected identifier after '.'");
            parts.add(identifier());
            advance();
        }
        Node value;
        if (accept(LPAREN)) {
            boolean loop = parts.size() == 1 && (parts.get(0)
                .equals("loop")
                || parts.get(0)
                    .equals("for_each"));
            if (loop) {
                loopDepth++;
            }
            List<Node> arguments = parseArguments();
            if (loop) {
                loopDepth--;
            }
            value = lowerCall(parts, arguments);
        } else {
            value = lowerRead(parts);
        }
        return parsePostfix(value);
    }

    private Node parsePostfix(Node value) throws MolangSyntaxException {
        while (true) {
            if (accept(DOT)) {
                require(IDENTIFIER, "expected identifier after '.'");
                String member = identifier();
                advance();
                value = member(value, member);
                continue;
            }
            if (accept(LBRACKET)) {
                Node index = parseExpression(0);
                require(RBRACKET, "expected ']'");
                advance();
                value = new Nodes.Index(value, index);
                continue;
            }
            if (kind == LPAREN) {
                throw fail("only named symbols may be invoked");
            }
            return value;
        }
    }

    private List<Node> parseArguments() throws MolangSyntaxException {
        List<Node> arguments = new ArrayList<>();
        if (accept(RPAREN)) {
            return arguments;
        }
        while (true) {
            arguments.add(parseExpression(0));
            if (accept(RPAREN)) {
                return arguments;
            }
            require(COMMA, "expected ',' between arguments");
            advance();
        }
    }

    private Node parseBlock() throws MolangSyntaxException {
        require(LBRACE, "expected '{'");
        advance();
        List<Node> children = new ArrayList<>();
        while (kind != RBRACE) {
            if (kind == EOF) {
                throw fail("unclosed block");
            }
            if (accept(SEMICOLON)) {
                continue;
            }
            children.add(parseExpression(0));
            if (kind != RBRACE && !accept(SEMICOLON)) {
                throw fail("expected ';' or '}'");
            }
        }
        advance();
        return new Nodes.Block(children.toArray(new Node[0]));
    }

    private Node parseReturn() throws MolangSyntaxException {
        advance();
        Node value = kind == SEMICOLON || kind == RBRACE || kind == EOF ? null : parseExpression(0);
        return new Nodes.Return(value);
    }

    // Lowering of names

    private Node lowerRead(List<String> parts) throws MolangSyntaxException {
        String root = parts.get(0);
        int n = parts.size();
        if (root.equals("null") && n == 1) {
            return new Nodes.Const(null);
        }
        if ((root.equals("roaming") || root.equals("r")) && n == 2) {
            return new Nodes.VariableRead("roaming." + parts.get(1));
        }
        if ((root.equals("v") || root.equals("variable")) && n >= 2) {
            if (parts.get(1)
                .equals("roaming") && n == 3) {
                return new Nodes.VariableRead("roaming." + parts.get(2));
            }
            if (parts.get(1)
                .startsWith(ROAMING_SLOT_PREFIX)) {
                throw fail("variable name uses the reserved roaming storage prefix");
            }
            return new Nodes.VariableRead(join(parts, 1));
        }
        if (root.equals("math") && n == 2
            && parts.get(1)
                .equals("pi")) {
            return new Nodes.Const(MolangValue.of((double) MolangMath.PI));
        }
        if ((root.equals("t") || root.equals("temp")) && n >= 2) {
            return tempRead(join(parts, 1));
        }
        Node value = external(parts, new ArrayList<>());
        for (int i = Math.min(2, n); i < n; i++) {
            value = member(value, parts.get(i));
        }
        return value;
    }

    private Node lowerCall(List<String> parts, List<Node> arguments) throws MolangSyntaxException {
        String root = parts.get(0);
        int n = parts.size();
        if (n == 1 && root.equals("loop")) {
            if (arguments.size() != 2) {
                throw fail("loop requires two arguments");
            }
            return new Nodes.Loop(arguments.get(0), arguments.get(1));
        }
        if (n == 1 && root.equals("for_each")) {
            if (arguments.size() != 3 || !(arguments.get(0) instanceof Nodes.TempRead)
                || ((Nodes.TempRead) arguments.get(0)).path.indexOf('.') >= 0) {
                throw fail("for_each requires a direct t.* local as its first argument");
            }
            return new Nodes.ForEach(((Nodes.TempRead) arguments.get(0)).slot, arguments.get(1), arguments.get(2));
        }
        if ((root.equals("v") || root.equals("variable") || root.equals("t") || root.equals("temp")) && n == 2) {
            // upstream: a variable is a known symbol of the wrong kind for a call, an independent error
            throw fail("variables cannot be invoked");
        }
        if (n > 2) {
            throw fail("invocation through a struct member is not supported");
        }
        return external(parts, arguments);
    }

    /** A host symbol: {@code ns.name}, or {@code name} in namespace {@code global}. */
    private Node external(List<String> parts, List<Node> arguments) {
        String root = parts.get(0);
        String namespace;
        String name;
        if (parts.size() == 1) {
            namespace = "global";
            name = root;
        } else {
            name = parts.get(1);
            switch (root) {
                case "q":
                case "query":
                    namespace = "query";
                    break;
                case "c":
                case "context":
                    namespace = "context";
                    break;
                default:
                    namespace = root;
            }
        }
        Node[] args = arguments.toArray(new Node[0]);
        if (namespace.equals("math")) {
            MolangMath.Fn fn = MolangMath.lookup(name);
            if (fn != null) {
                if (args.length < fn.minArgs || args.length > fn.maxArgs) {
                    // upstream: a callable with the wrong arity is UNSET, its arguments never run
                    return new Nodes.Const(null);
                }
                return Nodes.fold(new Nodes.MathCall(fn, args));
            }
        }
        int scratch = -1;
        if (args.length > 0) {
            scratch = scratchSizes.size();
            scratchSizes.add(args.length);
        }
        return new Nodes.HostCall(namespace, name, args, scratch);
    }

    private Node member(Node value, String member) {
        if (value instanceof Nodes.VariableRead) {
            return new Nodes.VariableRead(((Nodes.VariableRead) value).key + '.' + member);
        }
        if (value instanceof Nodes.TempRead) {
            return tempRead(((Nodes.TempRead) value).path + '.' + member);
        }
        return new Nodes.Member(value, Nodes.component(member));
    }

    private Nodes.TempRead tempRead(String path) {
        int dot = path.lastIndexOf('.');
        int parent = dot < 0 ? -1 : tempSlot(path.substring(0, dot));
        int component = dot < 0 ? -1 : Nodes.component(path.substring(dot + 1));
        return new Nodes.TempRead(path, tempSlot(path), parent, component);
    }

    private int tempSlot(String path) {
        Integer slot = temps.get(path);
        if (slot == null) {
            slot = temps.size();
            temps.put(path, slot);
        }
        return slot;
    }

    private Node assignment(Node target, Node value, int at) throws MolangSyntaxException {
        if (target instanceof Nodes.VariableRead) {
            return new Nodes.VariableAssign(((Nodes.VariableRead) target).key, value);
        }
        if (target instanceof Nodes.TempRead) {
            return new Nodes.TempAssign(((Nodes.TempRead) target).slot, value);
        }
        throw new MolangSyntaxException("invalid assignment target", at);
    }

    private static String join(List<String> parts, int from) {
        if (parts.size() == from + 1) {
            return parts.get(from);
        }
        StringBuilder builder = new StringBuilder(parts.get(from));
        for (int i = from + 1; i < parts.size(); i++) {
            builder.append('.')
                .append(parts.get(i));
        }
        return builder.toString();
    }

    // Tokens

    private String identifier() throws MolangSyntaxException {
        if (text.length() > MAX_IDENTIFIER) {
            throw fail("identifier longer than " + MAX_IDENTIFIER + " characters");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_')) {
                // upstream's lexer accepts any Unicode digit, its identifier rule only ASCII ones
                throw fail("invalid identifier '" + text + "'");
            }
        }
        return text;
    }

    private boolean accept(int expected) throws MolangSyntaxException {
        if (kind != expected) {
            return false;
        }
        advance();
        return true;
    }

    private void require(int expected, String message) throws MolangSyntaxException {
        if (kind != expected) {
            throw fail(message);
        }
    }

    private MolangSyntaxException fail(String message) {
        return new MolangSyntaxException(message, tokenStart);
    }

    private void advance() throws MolangSyntaxException {
        skipIgnored();
        text = null;
        tokenStart = offset;
        if (offset == source.length()) {
            kind = EOF;
            return;
        }
        char c = source.charAt(offset++);
        if (asciiLetter(c) || c == '_') {
            while (offset < source.length()) {
                char next = source.charAt(offset);
                if (!asciiLetter(next) && !Character.isDigit(next) && next != '_') {
                    break;
                }
                offset++;
            }
            text = source.substring(tokenStart, offset)
                .toLowerCase(Locale.ROOT);
            switch (text) {
                case "true":
                    kind = TRUE;
                    break;
                case "false":
                    kind = FALSE;
                    break;
                case "break":
                    kind = BREAK;
                    break;
                case "continue":
                    kind = CONTINUE;
                    break;
                case "return":
                    kind = RETURN;
                    break;
                default:
                    kind = IDENTIFIER;
            }
            return;
        }
        if (Character.isDigit(c) || c == '.' && offset < source.length() && Character.isDigit(source.charAt(offset))) {
            number(c);
            return;
        }
        if (c == '\'') {
            string();
            return;
        }
        switch (c) {
            case '.':
                kind = DOT;
                break;
            case '!':
                kind = match('=') ? BANG_EQ : BANG;
                break;
            case '&':
                kind = pair('&', AND_AND);
                break;
            case '|':
                kind = pair('|', OR_OR);
                break;
            case '<':
                kind = match('=') ? LTE : LT;
                break;
            case '>':
                kind = match('=') ? GTE : GT;
                break;
            case '=':
                kind = match('=') ? EQ_EQ : EQ;
                break;
            case '-':
                kind = match('>') ? ARROW : MINUS;
                break;
            case '?':
                kind = match('?') ? QUESTION_QUESTION : QUESTION;
                break;
            case '*':
                kind = STAR;
                break;
            case '/':
                kind = SLASH;
                break;
            case '+':
                kind = PLUS;
                break;
            case '(':
                kind = LPAREN;
                break;
            case ')':
                kind = RPAREN;
                break;
            case '{':
                kind = LBRACE;
                break;
            case '}':
                kind = RBRACE;
                break;
            case ':':
                kind = COLON;
                break;
            case '[':
                kind = LBRACKET;
                break;
            case ']':
                kind = RBRACKET;
                break;
            case ',':
                kind = COMMA;
                break;
            case ';':
                kind = SEMICOLON;
                break;
            default:
                throw new MolangSyntaxException("invalid character '" + c + "'", tokenStart);
        }
    }

    private void skipIgnored() {
        while (offset < source.length()) {
            char c = source.charAt(offset);
            if (Character.isWhitespace(c)) {
                offset++;
                continue;
            }
            if (c == '/' && offset + 1 < source.length() && source.charAt(offset + 1) == '/') {
                offset += 2;
                while (offset < source.length() && source.charAt(offset) != '\n' && source.charAt(offset) != '\r') {
                    offset++;
                }
                continue;
            }
            return;
        }
    }

    private void number(char first) throws MolangSyntaxException {
        boolean sawDot = first == '.';
        while (offset < source.length()) {
            char c = source.charAt(offset);
            if (Character.isDigit(c)) {
                offset++;
            } else if (c == '.' && !sawDot) {
                sawDot = true;
                offset++;
            } else {
                break;
            }
        }
        if (offset < source.length() && (source.charAt(offset) == 'e' || source.charAt(offset) == 'E')) {
            offset++;
            if (offset < source.length() && (source.charAt(offset) == '+' || source.charAt(offset) == '-')) {
                offset++;
            }
            int exponentStart = offset;
            while (offset < source.length() && Character.isDigit(source.charAt(offset))) {
                offset++;
            }
            if (exponentStart == offset) {
                throw new MolangSyntaxException("numeric exponent has no digits", tokenStart);
            }
        }
        kind = NUMBER;
        text = source.substring(tokenStart, offset);
    }

    private void string() throws MolangSyntaxException {
        int close = source.indexOf('\'', offset);
        if (close < 0) {
            throw new MolangSyntaxException("unclosed string literal", tokenStart);
        }
        String value = source.substring(offset, close);
        offset = close + 1;
        if (value.length() > MAX_STRING_BYTES / 3 && value.getBytes(StandardCharsets.UTF_8).length > MAX_STRING_BYTES) {
            throw new MolangSyntaxException("string literal exceeds " + MAX_STRING_BYTES + " UTF-8 bytes", tokenStart);
        }
        kind = STRING;
        text = value;
    }

    private int pair(char expected, int pairKind) throws MolangSyntaxException {
        if (!match(expected)) {
            throw new MolangSyntaxException("expected paired '" + expected + "'", tokenStart);
        }
        return pairKind;
    }

    private boolean match(char expected) {
        if (offset >= source.length() || source.charAt(offset) != expected) {
            return false;
        }
        offset++;
        return true;
    }

    private static boolean asciiLetter(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }
}
