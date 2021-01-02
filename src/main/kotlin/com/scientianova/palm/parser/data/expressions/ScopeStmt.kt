package com.scientianova.palm.parser.data.expressions

import com.scientianova.palm.parser.data.top.DecModifier
import com.scientianova.palm.parser.data.top.Import

sealed class ScopeStmt {
    data class Expr(val value: PExpr) : ScopeStmt()
    data class Defer(val body: PExprScope) : ScopeStmt()
    data class Imp(val import: Import) : ScopeStmt()

    data class Dec(
        val modifiers: List<DecModifier>,
        val mutable: Boolean,
        val pattern: PDecPattern,
        val type: PType,
        val expr: PExpr?
    ) : ScopeStmt()
}