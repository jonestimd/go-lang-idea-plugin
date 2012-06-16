package ro.redeul.google.go.lang.parser;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import com.intellij.lang.ASTNode;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import ro.redeul.google.go.lang.lexer.GoTokenTypes;
import ro.redeul.google.go.lang.parser.parsing.declarations.Declaration;
import ro.redeul.google.go.lang.parser.parsing.declarations.FunctionOrMethodDeclaration;
import ro.redeul.google.go.lang.parser.parsing.expressions.Expressions;
import ro.redeul.google.go.lang.parser.parsing.helpers.IdentifierList;
import ro.redeul.google.go.lang.parser.parsing.statements.BlockStatement;
import ro.redeul.google.go.lang.parser.parsing.statements.Statements;
import ro.redeul.google.go.lang.parser.parsing.toplevel.CompilationUnit;
import ro.redeul.google.go.lang.parser.parsing.types.Types;
import ro.redeul.google.go.lang.parser.parsing.util.ParserUtils;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: Jul 24, 2010
 * Time: 7:31:03 PM
 */
public class GoParser extends ParserUtils implements PsiParser {

    public enum ParsingFlag {
        Debug,
        WrapCompositeInExpression,
        AllowCompositeLiteral,
        ParseIota
    }

    EnumSet<ParsingFlag> flags = EnumSet.noneOf(ParsingFlag.class);

    Set<String> packageNames = new HashSet<String>();

    public boolean isSet(ParsingFlag parseFlag) {
        return flags.contains(parseFlag);
    }

    public void setFlag(ParsingFlag parsingFlag) {
        flags.add(parsingFlag);
    }

    public boolean resetFlag(ParsingFlag parsingFlag, boolean state) {
        boolean oldState = flags.contains(parsingFlag);

        if (state)
            setFlag(parsingFlag);
        else
            unsetFlag(parsingFlag);

        return oldState;
    }

    public void unsetFlag(ParsingFlag parsingFlag) {
        flags.remove(parsingFlag);
    }

    @NotNull
    public ASTNode parse(IElementType root, PsiBuilder builder) {

        boolean debugging = false;
        builder.setDebugMode(debugging);

        resetFlag(ParsingFlag.AllowCompositeLiteral, true);
        resetFlag(ParsingFlag.ParseIota, false);
        resetFlag(ParsingFlag.WrapCompositeInExpression, true);
        resetFlag(ParsingFlag.Debug, true);
        packageNames.clear();

        PsiBuilder.Marker rootMarker = builder.mark();

        CompilationUnit.parse(builder, this);

        while ( ! builder.eof() ) {
            builder.advanceLexer();
        }

        rootMarker.done(root);

        return builder.getTreeBuilt();
    }

    public boolean parseTopLevelDeclarations(PsiBuilder builder) {

        while ( ! builder.eof() ) {

            if ( parseTopLevelDeclaration(builder) == null ) {
                ParserUtils.wrapError(builder, "unknown.token");
            }

            ParserUtils.skipNLS(builder);
        }

        return true;
    }

    private IElementType parseTopLevelDeclaration(PsiBuilder builder) {

        ParserUtils.skipNLS(builder);

        if (lookAhead(builder, GoTokenTypes.kFUNC))
            return FunctionOrMethodDeclaration.parse(builder, this);

        return Declaration.parse(builder, this);
    }

    public boolean parseExpression(PsiBuilder builder) {
        return Expressions.parse(builder, this);
    }

    public IElementType parseType(PsiBuilder builder) {
        return Types.parseTypeDeclaration(builder, this);
    }

    public int parseIdentifierList(PsiBuilder builder) {
        return parseIdentifierList(builder, true);
    }

    public int parseIdentifierList(PsiBuilder builder, boolean markList) {
        return IdentifierList.parse(builder, this, markList);
    }

    public IElementType parseBody(PsiBuilder builder) {
        return BlockStatement.parse(builder, this);
    }

    public IElementType parseStatement(PsiBuilder builder) {
        return Statements.parse(builder, this);
    }

    public IElementType parseStatementSimple(PsiBuilder builder) {
        return Statements.parseSimple(builder, this);
    }

    public boolean parseTypeName(PsiBuilder builder) {
        return Types.parseTypeName(builder, this);
    }

    public boolean parseMethodSignature(PsiBuilder builder) {
        return FunctionOrMethodDeclaration.parseSignature(builder, this);
    }

    public int parseExpressionList(PsiBuilder builder) {
        return Expressions.parseList(builder, this);
    }

    public int tryParseExpressionList(PsiBuilder builder) {
        PsiBuilder.Marker mark = builder.mark();
        int expressionCount = Expressions.parseList(builder, this);
        if ( expressionCount == 0 ) {
            mark.rollbackTo();
        } else {
            mark.drop();
        }

        return expressionCount;
    }

    public boolean parsePrimaryExpression(PsiBuilder builder) {
        return Expressions.parsePrimary(builder, this);
    }

    public boolean parseFunctionSignature(PsiBuilder builder) {
        return FunctionOrMethodDeclaration.parseCompleteMethodSignature(builder, this);
    }

    public int parseTypeList(PsiBuilder builder) {
        return Types.parseTypeDeclarationList(builder, this);
    }

    public boolean tryParseSimpleStmt(PsiBuilder builder) {
        return Statements.tryParseSimple(builder, this);
    }

    public void setKnownPackage(String packageName) {
        packageNames.add(packageName);
    }

    public boolean isPackageName(String name) {
        return packageNames.contains(name);
    }
}
