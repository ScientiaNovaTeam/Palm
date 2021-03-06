package com.palmlang.palm.parser.top

import com.palmlang.palm.lexer.Token
import com.palmlang.palm.parser.Parser
import com.palmlang.palm.ast.top.Annotation
import com.palmlang.palm.ast.top.AnnotationType
import com.palmlang.palm.ast.top.DecModifier
import com.palmlang.palm.ast.top.PDecMod
import com.palmlang.palm.lexer.fileIdent
import com.palmlang.palm.parser.parseIdent
import com.palmlang.palm.parser.expressions.parseCallArgs
import com.palmlang.palm.util.*

fun Parser.identToDecMod(string: String): Positioned<DecModifier>? = when (string) {
    "inline" -> DecModifier.Inline.end()
    "override" -> DecModifier.Override.end()
    "open" -> DecModifier.Open.end()
    "final" -> DecModifier.Final.end()
    "public" -> DecModifier.Public.end()
    "protected" -> DecModifier.Protected.end()
    "private" -> {
        val (_, startPos, endPos) = currentWithPos
        val maybeParens = advance().current
        if (maybeParens is Token.Parens)
            parenthesizedOf(maybeParens.tokens).parseModifierParam { type, path ->
                DecModifier.Private(type, path).end(startPos)
            }
        else DecModifier.Private(null, emptyList()).at(startPos, endPos)
    }
    "abstract" -> DecModifier.Abstract.end()
    "data" -> DecModifier.Data.end()
    "annotation" -> DecModifier.Ann.end()
    "sealed" -> {
        val (_, startPos, endPos) = currentWithPos
        val maybeParens = advance().current
        if (maybeParens is Token.Parens)
            parenthesizedOf(maybeParens.tokens).parseModifierParam { type, path ->
                DecModifier.Sealed(type, path).end(startPos)
            }
        else DecModifier.Sealed(null, emptyList()).at(startPos, endPos)
    }
    "local" -> DecModifier.Local.end()
    "noinline" -> DecModifier.NoInline.end()
    "crossinline" -> DecModifier.CrossInline.end()
    "operator" -> DecModifier.Operator.end()
    else -> null
}

fun Parser.parseDecModifiers(): List<PDecMod> = recBuildList {
    when (val token = current) {
        is Token.Ident -> if (token.backticked) return this else when (next) {
            Token.Colon, Token.End, Token.Comma, Token.Assign -> return this
            else -> add(identToDecMod(token.name) ?: return this)
        }
        Token.At -> parseAnnotation()?.let { add(it.map(DecModifier::Annotation)) } ?: return this
        else -> return this
    }
}

inline fun Parser.parseModifierParam(fn: (PathType, Path) -> Positioned<DecModifier>): Positioned<DecModifier>? =
    when (val curr = current) {
        Token.Mod -> advance().parseModifierPath(mutableListOf())?.let { path -> fn(PathType.Module, path) }
        Token.SuMod -> advance().parseModifierPath(mutableListOf())?.let { path -> fn(PathType.Super, path) }
        is Token.Ident -> parseModifierPath(mutableListOf(curr.name.end()))?.let { path -> fn(PathType.Root, path) }
        else -> null
    }

fun Parser.parseModifierPath(start: MutableList<PString>): Path? = recBuildList(start) {
    when (current) {
        Token.End -> return this
        Token.Dot -> {
            val ident = advance().current
            if (ident is Token.Ident) add(ident.name.end())
            else return null
        }
        else -> return null
    }
}


private fun Parser.parseAnnotationType(ident: String): AnnotationType = if (next == Token.Colon) {
    advance().advance()
    when (ident) {
        "file" -> AnnotationType.File
        "delegate" -> AnnotationType.Delegate
        "get" -> AnnotationType.Get
        "set" -> AnnotationType.Set
        "init" -> AnnotationType.Init
        "property" -> AnnotationType.Property
        "field" -> AnnotationType.Field
        "param" -> AnnotationType.Param
        "setParam" -> AnnotationType.SetParam
        else -> {
            err("Unknown annotation type")
            AnnotationType.Normal
        }
    }
} else AnnotationType.Normal

fun Parser.parseAnnotation(): Positioned<Annotation>? = withPos { startPos ->
    val start = rawLookup(1)
    if (start is Token.Ident) advance() else {
        err("Missing identifier")
        return null
    }

    val type = if (start.backticked) AnnotationType.Normal else parseAnnotationType(start.name)
    val path = parseAnnotationPath()

    val end: StringPos
    val args = current.let { paren ->
        if (paren is Token.Parens)
            parenthesizedOf(paren.tokens).parseCallArgs().also {
                end = nextPos
                advance()
            }
        else {
            end = path.last().next
            emptyList()
        }
    }

    return Annotation(path, args, type).at(startPos, end)
}

private fun Parser.parseAnnotationPath() = recBuildList(mutableListOf(parseIdent())) {
    if (current == Token.Dot) {
        add(advance().parseIdent())
    } else {
        return this
    }
}

fun Parser.parseFileAnnotations(): List<Annotation> = recBuildList {
    val startIdent = index
    if (current == Token.At && rawLookup(1) === fileIdent && advance().advance().current == Token.Colon) {
        val path = advance().parseAnnotationPath()
        val args = inParensOrEmpty(fn = Parser::parseCallArgs).also { advance() }
        add(Annotation(path, args, AnnotationType.File))
    } else {
        index = startIdent
        return this
    }
}