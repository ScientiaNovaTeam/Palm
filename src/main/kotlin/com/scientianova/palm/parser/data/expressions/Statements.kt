package com.scientianova.palm.parser.data.expressions

import com.scientianova.palm.parser.data.types.PType

sealed class ScopeStatement

data class DecStatement(
    val pattern: PDecPattern,
    val mutable: Boolean,
    val type: PType?,
    val expr: PExpr?
) : ScopeStatement()

data class AssignStatement(
    val left: PExpr,
    val type: AssignmentType,
    val right: PExpr
) : ScopeStatement()

data class GuardStatement(
    val cond: List<Condition>,
    val body: ExprScope
) : ScopeStatement()

data class UsingStatement(val expr: PExpr) : ScopeStatement()
data class ExprStatement(val expr: PExpr) : ScopeStatement()

data class ExprScope(val statements: List<ScopeStatement>)