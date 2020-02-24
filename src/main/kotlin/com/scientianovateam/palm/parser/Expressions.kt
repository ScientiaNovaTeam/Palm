package com.scientianovateam.palm.parser

import com.scientianovateam.palm.tokenizer.*
import com.scientianovateam.palm.util.Positioned
import com.scientianovateam.palm.util.on
import com.scientianovateam.palm.util.pushing
import com.scientianovateam.palm.util.safePop
import java.util.*

interface IExpression {
    override fun toString(): String
}

typealias PositionedExpression = Positioned<IExpression>

data class Constant(val name: String) : IExpression
data class Num(val num: Double) : IExpression
data class Chr(val char: Char) : IExpression
data class Bool(val bool: Boolean) : IExpression
data class BinOp(val operator: OperatorToken, val first: IExpression, val second: IExpression) : IExpression
data class Cast(val expr: IExpression, val type: IType) : IExpression
data class TypeCheck(val expr: IExpression, val type: IType, val inverted: Boolean = false) : IExpression
data class UnOp(val operator: OperatorToken, val expr: IExpression) : IExpression

object Null : IExpression {
    override fun toString() = "Null"
}

data class Lis(val expressions: List<IExpression> = emptyList()) : IExpression {
    constructor(expr: IExpression) : this(listOf(expr))
}

data class Getter(val expression: IExpression, val params: List<IExpression>) : IExpression
data class Dict(val values: Map<IExpression, IExpression>) : IExpression
data class Range(val first: IExpression, val second: IExpression?, val last: IExpression) : IExpression
data class Comprehension(
    val expression: IExpression,
    val name: String,
    val list: IExpression,
    val filter: IExpression? = null
) : IExpression

data class Str(val parts: List<StrPart>) : IExpression
sealed class StrPart
data class StrStringPart(val string: String) : StrPart()
data class StrExpressionPart(val expr: IExpression) : StrPart()

data class Object(val values: Map<String, IExpression> = emptyMap(), val type: IType? = null) : IExpression
data class ValAccess(val expr: IExpression, val field: String) : IExpression
data class If(val condExpr: IExpression, val thenExpr: IExpression, val elseExpr: IExpression) : IExpression
data class Where(val expr: IExpression, val definitions: List<Pair<String, IExpression>>) : IExpression
data class When(val branches: List<Pair<IExpression, IExpression>>, val elseBranch: IExpression?) : IExpression
data class WhenSwitch(val value: IExpression, val branches: List<SwitchBranch>, val elseBranch: IExpression?) :
    IExpression
typealias SwitchBranch = Pair<List<Pattern>, IExpression>

sealed class Pattern
data class ExpressionPattern(val expression: IExpression) : Pattern()
data class TypePattern(val type: IType, val inverted: Boolean = false) : Pattern()
data class ContainingPattern(val collection: IExpression, val inverted: Boolean = false) : Pattern()
data class ComparisonPattern(val operator: ComparisonOperatorToken, val expression: IExpression) : Pattern()

fun handleExpression(stack: TokenStack, token: PositionedToken?): Pair<PositionedExpression, PositionedToken?> {
    val (first, op) = handleExpressionPart(stack, token)
    val expr: PositionedExpression
    val next: PositionedToken?
    if (op != null && op.value is OperatorToken && op.rows.last == first.rows.first) {
        val res = handleBinOps(stack, op.value, first.rows.first, Stack<IExpression>().pushing(first.value))
        expr = res.first
        next = res.second
    } else {
        expr = first
        next = op
    }
    return if (next?.value is WhereToken) {
        if (stack.safePop()?.value is OpenCurlyBracketToken) handleWhere(
            stack,
            stack.safePop(),
            expr.value,
            expr.rows.first
        )
        else error("Missing open curly bracket after where")
    } else expr to next
}

fun handleBinOps(
    stack: TokenStack,
    op: OperatorToken,
    startRow: Int,
    operandStack: Stack<IExpression>,
    operatorStack: Stack<OperatorToken> = Stack()
): Pair<PositionedExpression, PositionedToken?> =
    if (operatorStack.isEmpty() || op.precedence > operatorStack.peek().precedence)
        when (op) {
            is TypeOperatorToken -> {
                val (type, next) = handleType(stack, stack.safePop())
                operandStack.push(TypeCheck(operandStack.pop(), type.value, op is IsNotToken))
                if (next != null && next.value is OperatorToken && next.rows.last == type.rows.first)
                    handleBinOps(stack, next.value, startRow, operandStack, operatorStack)
                else emptyStacks(next, startRow..type.rows.last, operandStack, operatorStack)
            }
            is AsToken -> {
                val (type, next) = handleType(stack, stack.safePop())
                operandStack.push(Cast(operandStack.pop(), type.value))
                if (next != null && next.value is OperatorToken && next.rows.last == type.rows.first)
                    handleBinOps(stack, next.value, startRow, operandStack, operatorStack)
                else emptyStacks(next, startRow..type.rows.last, operandStack, operatorStack)
            }
            else -> {
                val (operand, next) = handleExpressionPart(stack, stack.safePop())
                operatorStack.push(op)
                operandStack.push(operand.value)
                if (next != null && next.value is OperatorToken && next.rows.last == operand.rows.first) {
                    if (op is ComparisonOperatorToken && next.value is ComparisonOperatorToken) {
                        operatorStack.push(AndToken)
                        operandStack.push(operand.value)
                    }
                    handleBinOps(stack, next.value, startRow, operandStack, operatorStack)
                } else emptyStacks(next, startRow..operand.rows.last, operandStack, operatorStack)
            }
        }
    else {
        val second = operandStack.pop()
        handleBinOps(
            stack, op, startRow, operandStack.pushing(BinOp(operatorStack.pop(), operandStack.pop(), second)),
            operatorStack
        )
    }

fun emptyStacks(
    next: PositionedToken?,
    rows: IntRange,
    operandStack: Stack<IExpression>,
    operatorStack: Stack<OperatorToken> = Stack()
): Pair<PositionedExpression, PositionedToken?> = if (operatorStack.isEmpty()) operandStack.pop() on rows to next else {
    val second = operandStack.pop()
    emptyStacks(next, rows, operandStack.pushing(BinOp(operatorStack.pop(), operandStack.pop(), second)), operatorStack)
}

fun handleExpressionPart(stack: TokenStack, token: PositionedToken?): Pair<PositionedExpression, PositionedToken?> {
    val (expr, afterExpr) = if (token == null) error("Missing expression part") else when (token.value) {
        is NumberToken -> Num(token.value.number) on token to stack.safePop()
        is CharToken -> Chr(token.value.char) on token to stack.safePop()
        is BoolToken -> Bool(token.value.bool) on token to stack.safePop()
        is NullToken -> Null on token to stack.safePop()
        is UncapitalizedIdentifierToken -> Constant(token.value.name) on token to stack.safePop()
        is CapitalizedIdentifierToken -> {
            val (type, bracket) = handleType(stack, token)
            if (bracket?.value is OpenCurlyBracketToken)
                handleObject(stack, stack.safePop(), token.rows.first, type.value)
            else error("Missing curly bracket after type name")
        }
        is OpenParenToken -> {
            val (expr, closedParen) = handleExpression(stack, stack.safePop())
            if (closedParen?.value is ClosedParenToken) expr to stack.safePop() else error("Unclosed parenthesis")
        }
        is OpenSquareBracketToken -> {
            val first = stack.safePop()
            if (first?.value is ClosedSquareBracketToken)
                Lis() on token.rows.first..first.rows.last to stack.safePop()
            else {
                val (expr, next) = handleExpression(stack, first)
                when (next?.value) {
                    is CommaToken ->
                        handleSecondInList(stack, stack.safePop(), token.rows.first, expr.value)
                    is SemicolonToken ->
                        handleList(stack, stack.safePop(), token.rows.first, emptyList(), listOf(Lis(expr.value)))
                    is ColonToken -> {
                        val (value, newNext) = handleExpression(stack, stack.safePop())
                        if (newNext?.value is CommaToken)
                            handleDict(stack, stack.safePop(), token.rows.first, mapOf(expr.value to value.value))
                        else handleDict(stack, newNext, token.rows.first, mapOf(expr.value to value.value))
                    }
                    is DoubleDotToken -> {
                        val (last, closedBracket) = handleExpression(stack, stack.safePop())
                        if (closedBracket?.value is ClosedSquareBracketToken)
                            Range(expr.value, null, last.value) on token.rows.first..closedBracket.rows.last to
                                    stack.safePop()
                        else error("Unclosed square bracket")
                    }
                    is ForToken -> handleComprehension(stack, stack.safePop(), expr.value, token.rows.first)
                    else -> handleSecondInList(stack, next, token.rows.first, expr.value)
                }
            }
        }
        is PureStringToken -> Str(listOf(StrStringPart(token.value.name))) on token to stack.safePop()
        is StringTemplateToken -> Str(token.value.parts.map {
            when (it) {
                is StringPart -> StrStringPart(it.string)
                is TokensPart -> {
                    val (expr, nullToken) = handleExpression(it.tokens, it.tokens.safePop())
                    if (nullToken != null) error("Invalid interpolated expression")
                    StrExpressionPart(expr.value)
                }
            }
        }) on token to stack.safePop()
        is OpenCurlyBracketToken -> handleObject(stack, stack.safePop(), token.rows.first)
        is OperatorToken -> {
            val (expr, next) = handleExpressionPart(stack, stack.safePop())
            UnOp(token.value, expr.value) on token.rows.first..expr.rows.last to next
        }
        is IfToken -> handleIf(stack, stack.safePop(), token.rows.first)
        is WhenToken -> {
            val afterWhen = stack.safePop()
            if (afterWhen?.value is OpenCurlyBracketToken) handleWhen(stack, stack.safePop(), token.rows.first)
            else {
                val (expr, bracket) = handleExpression(stack, stack.safePop())
                if (bracket?.value is OpenCurlyBracketToken)
                    handleWhenSwitch(stack, stack.safePop(), token.rows.first, expr.value)
                else error("Missing curly bracket after when")
            }
        }
        else -> error("Invalid expression part")
    }
    val (accessed, next) = handleAccess(stack, afterExpr, expr)
    return if (next != null && next.rows.first == accessed.rows.last && next.value is OpenSquareBracketToken)
        handleGetter(stack, stack.safePop(), accessed.value, accessed.rows.first)
    else accessed to next
}

fun handleSecondInList(
    stack: TokenStack,
    token: PositionedToken?,
    startRow: Int,
    first: IExpression
): Pair<PositionedExpression, PositionedToken?> = when {
    token == null -> error("Unclosed square bracket")
    token.value is ClosedSquareBracketToken -> Lis(first) on startRow..token.rows.last to stack.safePop()
    else -> {
        val (expr, next) = handleExpression(stack, token)
        when (next?.value) {
            is ClosedSquareBracketToken ->
                Lis(listOf(first, expr.value)) on startRow..token.rows.last to stack.safePop()
            is CommaToken ->
                handleList(stack, stack.safePop(), startRow, listOf(first, expr.value))
            is SemicolonToken ->
                handleList(stack, stack.safePop(), startRow, emptyList(), listOf(Lis(listOf(first, expr.value))))
            is DoubleDotToken -> {
                val (last, closedBracket) = handleExpression(stack, stack.safePop())
                if (closedBracket?.value is ClosedSquareBracketToken)
                    Range(first, expr.value, last.value) on startRow..closedBracket.rows.last to stack.safePop()
                else error("Unclosed square bracket")
            }
            else -> handleList(stack, next, startRow, listOf(first, expr.value))
        }
    }
}

fun handleList(
    stack: TokenStack,
    token: PositionedToken?,
    startRow: Int,
    values: List<IExpression>,
    lists: List<Lis> = emptyList()
): Pair<Positioned<Lis>, PositionedToken?> = when {
    token == null -> error("Unclosed square bracket")
    token.value is ClosedSquareBracketToken ->
        (if (lists.isEmpty()) Lis(values) else Lis(lists + Lis(values))) on startRow..token.rows.last to stack.safePop()
    else -> {
        val (expr, next) = handleExpression(stack, token)
        when (next?.value) {
            is ClosedSquareBracketToken ->
                (if (lists.isEmpty()) Lis(values + expr.value) else Lis(lists + Lis(values + expr.value))) on
                        startRow..token.rows.last to stack.safePop()
            is CommaToken ->
                handleList(stack, stack.safePop(), startRow, values + expr.value, lists)
            is SemicolonToken ->
                handleList(stack, stack.safePop(), startRow, emptyList(), lists + Lis(values + expr.value))
            else -> handleList(stack, next, startRow, values + expr.value, lists)
        }
    }
}

fun handleDict(
    stack: TokenStack,
    token: PositionedToken?,
    startRow: Int,
    values: Map<IExpression, IExpression>
): Pair<Positioned<Dict>, PositionedToken?> = when {
    token == null -> error("Unclosed square bracket")
    token.value is ClosedSquareBracketToken -> Dict(values) on startRow..token.rows.last to stack.safePop()
    else -> {
        val (key, colon) = handleExpression(stack, token)
        if (colon?.value !is ColonToken) error("Missing colon in dict")
        val (value, next) = handleExpression(stack, stack.safePop())
        when (next?.value) {
            is ClosedSquareBracketToken ->
                Dict(values + (key.value to value.value)) on startRow..token.rows.last to stack.safePop()
            is CommaToken ->
                handleDict(stack, stack.safePop(), startRow, values + (key.value to value.value))
            else -> handleDict(stack, colon, startRow, values + (key.value to value.value))
        }
    }
}

fun handleComprehension(
    stack: TokenStack,
    token: PositionedToken?,
    expr: IExpression,
    startRow: Int
): Pair<PositionedExpression, PositionedToken?> {
    if (token == null || token.value !is UncapitalizedIdentifierToken)
        error("Invalid variable name in list comprehension")
    val name = token.value.name
    if (stack.safePop()?.value !is InToken) error("Missing `in` in list comprehension")
    val (collection, afterCollection) = handleExpression(stack, stack.safePop())
    return when (afterCollection?.value) {
        is ClosedSquareBracketToken ->
            Comprehension(expr, name, collection.value) on startRow..afterCollection.rows.last to stack.safePop()
        is ForToken -> {
            val (nested, next) = handleComprehension(stack, stack.safePop(), expr, startRow)
            Comprehension(nested.value, name, collection.value) on startRow..afterCollection.rows.last to next
        }
        is IfToken -> {
            val (filter, afterFilter) = handleExpression(stack, stack.safePop())
            when (afterFilter?.value) {
                is ClosedSquareBracketToken -> Comprehension(expr, name, collection.value, filter.value) on
                        startRow..afterCollection.rows.last to stack.safePop()
                is ForToken -> {
                    val (nested, next) = handleComprehension(stack, stack.safePop(), expr, startRow)
                    Comprehension(nested.value, name, collection.value, filter.value) on
                            startRow..afterCollection.rows.last to next
                }
                else -> error("Unclosed square bracket")
            }
        }
        else -> error("Unclosed square bracket")
    }
}

fun handleObject(
    stack: TokenStack,
    token: PositionedToken?,
    startRow: Int,
    type: IType? = null,
    values: Map<String, IExpression> = emptyMap()
): Pair<Positioned<Object>, PositionedToken?> = if (token == null) error("Unclosed object") else when (token.value) {
    is ClosedCurlyBracketToken -> Object(values, type) on startRow..token.rows.last to stack.safePop()
    is IKeyToken -> stack.safePop().let { assignToken ->
        val (expr, next) = when (assignToken?.value) {
            is AssignmentToken -> handleExpression(stack, stack.safePop())
            is OpenCurlyBracketToken -> handleObject(stack, stack.safePop(), assignToken.rows.first)
            else -> error("Missing equals sign")
        }
        when (next?.value) {
            is ClosedCurlyBracketToken ->
                Object(values + (token.value.name to expr.value), type) on
                        startRow..token.rows.last to stack.safePop()
            is CommaToken ->
                handleObject(stack, stack.safePop(), startRow, type, values + (token.value.name to expr.value))
            else -> handleObject(stack, next, startRow, type, values + (token.value.name to expr.value))
        }
    }
    else -> error("Invalid key name")
}

fun handleIf(stack: TokenStack, token: PositionedToken?, startRow: Int): Pair<Positioned<If>, PositionedToken?> {
    val (cond, thenToken) = handleExpression(stack, token)
    if (thenToken?.value !is ThenToken) error("Missing then")
    val (thenExpr, elseToken) = handleExpression(stack, stack.safePop())
    if (elseToken?.value !is ElseToken) error("Missing else")
    val (elseExpr, next) = handleExpression(stack, stack.safePop())
    return If(cond.value, thenExpr.value, elseExpr.value) on startRow..elseExpr.rows.last to next
}

fun handleAccess(
    stack: TokenStack,
    token: PositionedToken?,
    expr: PositionedExpression
): Pair<PositionedExpression, PositionedToken?> = if (token?.value is DotToken) {
    val name = stack.safePop()
    if (name == null || name.value !is UncapitalizedIdentifierToken) error("Invalid accessor name")
    handleAccess(stack, stack.safePop(), ValAccess(expr.value, name.value.name) on expr.rows.first..name.rows.last)
} else expr to token

fun handleGetter(
    stack: TokenStack,
    token: PositionedToken?,
    expr: IExpression,
    startRow: Int,
    params: List<IExpression> = emptyList()
): Pair<PositionedExpression, PositionedToken?> = when {
    token == null -> error("Unclosed square bracket")
    token.value is ClosedSquareBracketToken -> Getter(expr, params) on startRow..token.rows.last to stack.safePop()
    else -> {
        val (currentExpr, next) = handleExpression(stack, token)
        when (next?.value) {
            is ClosedSquareBracketToken ->
                Getter(expr, params + currentExpr.value) on startRow..token.rows.last to stack.safePop()
            is CommaToken ->
                handleGetter(stack, stack.safePop(), expr, startRow, params + currentExpr.value)
            else -> handleGetter(stack, next, expr, startRow, params + currentExpr.value)
        }
    }
}

fun handleWhere(
    stack: TokenStack,
    token: PositionedToken?,
    expression: IExpression,
    startRow: Int,
    values: List<Pair<String, IExpression>> = emptyList()
): Pair<Positioned<Where>, PositionedToken?> = if (token == null) error("Unclosed where") else when (token.value) {
    is ClosedCurlyBracketToken -> Where(expression, values) on startRow..token.rows.last to stack.safePop()
    is IKeyToken -> stack.safePop().let { assignToken ->
        val (expr, next) = when (assignToken?.value) {
            is AssignmentToken -> handleExpression(stack, stack.safePop())
            is OpenCurlyBracketToken -> handleObject(stack, stack.safePop(), assignToken.rows.first)
            else -> error("Missing equals sign")
        }
        when (next?.value) {
            is ClosedCurlyBracketToken ->
                Where(expression, values + (token.value.name to expr.value)) on
                        startRow..token.rows.last to stack.safePop()
            is CommaToken ->
                handleWhere(stack, stack.safePop(), expression, startRow, values + (token.value.name to expr.value))
            else -> handleWhere(stack, next, expression, startRow, values + (token.value.name to expr.value))
        }
    }
    else -> error("Invalid key name")
}

fun handleWhen(
    stack: TokenStack,
    token: PositionedToken?,
    startRow: Int,
    branches: List<Pair<IExpression, IExpression>> = emptyList(),
    elseExpr: IExpression? = null
): Pair<Positioned<When>, PositionedToken?> = when (token?.value) {
    is ClosedCurlyBracketToken -> When(branches, elseExpr) on startRow..token.rows.last to stack.safePop()
    is ElseToken -> {
        if (stack.safePop()?.value !is ArrowToken) error("Missing arrow in when expression")
        val (expr, next) = handleExpression(stack, stack.safePop())
        if (elseExpr == null) handleWhen(stack, next, startRow, branches, expr.value)
        else handleWhen(stack, next, startRow, branches, elseExpr)
    }
    else -> {
        val (condition, arrow) = handleExpression(stack, token)
        if (arrow?.value !is ArrowToken) error("Missing arrow in when expression")
        val (expr, next) = handleExpression(stack, stack.safePop())
        if (elseExpr == null) handleWhen(stack, next, startRow, branches + (condition.value to expr.value))
        else handleWhen(stack, next, startRow, branches, elseExpr)
    }
}

fun handleWhenSwitch(
    stack: TokenStack,
    token: PositionedToken?,
    startRow: Int,
    value: IExpression,
    branches: List<SwitchBranch> = emptyList(),
    elseExpr: IExpression? = null
): Pair<PositionedExpression, PositionedToken?> = when (token?.value) {
    is ClosedCurlyBracketToken -> WhenSwitch(value, branches, elseExpr) on startRow..token.rows.last to stack.safePop()
    is ElseToken -> {
        if (stack.safePop()?.value !is ArrowToken) error("Missing arrow in when expression")
        val (expr, next) = handleExpression(stack, stack.safePop())
        if (elseExpr == null) handleWhenSwitch(stack, next, startRow, value, branches, expr.value)
        else handleWhenSwitch(stack, next, startRow, value, branches, elseExpr)
    }
    else -> {
        val (branch, next) = handleSwitchBranch(stack, token)
        if (elseExpr == null) handleWhenSwitch(stack, next, startRow, value, branches, elseExpr)
        else handleWhenSwitch(stack, next, startRow, value, branches + branch)
    }
}

fun handleSwitchBranch(
    stack: TokenStack,
    token: PositionedToken?,
    patterns: List<Pattern> = emptyList()
): Pair<SwitchBranch, PositionedToken?> {
    if (token == null) error("Unclosed when expression")
    val (pattern, separator) = when (token.value) {
        is TypeOperatorToken -> {
            val (type, next) = handleType(stack, stack.safePop())
            TypePattern(type.value, token.value is IsNotToken) to next
        }
        is ContainingOperatorToken -> {
            val (expr, next) = handleExpression(stack, stack.safePop())
            ContainingPattern(expr.value, token.value is NotInToken) to next
        }
        is ComparisonOperatorToken -> {
            val (expr, next) = handleExpression(stack, stack.safePop())
            ComparisonPattern(token.value, expr.value) to next
        }
        else -> {
            val (expr, next) = handleExpression(stack, token)
            ExpressionPattern(expr.value) to next
        }
    }
    return when (separator?.value) {
        is CommaToken -> handleSwitchBranch(stack, stack.safePop(), patterns + pattern)
        is ArrowToken -> {
            val (res, next) = handleExpression(stack, stack.safePop())
            patterns + pattern to res.value to next
        }
        else -> error("Missing arrow in when expression")
    }
}