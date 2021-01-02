package com.scientianova.palm.parser.data.types

import com.scientianova.palm.parser.data.expressions.*
import com.scientianova.palm.parser.data.top.DecModifier
import com.scientianova.palm.parser.data.top.FunParam
import com.scientianova.palm.parser.data.top.Function
import com.scientianova.palm.util.PString
import com.scientianova.palm.util.Positioned

enum class DecHandling {
    None, Val, Var
}

sealed class SuperType {
    data class Class(val type: PType, val args: List<Arg<PExpr>>) : SuperType()
    data class Interface(val type: PType, val delegate: PString?) : SuperType()
}

typealias PSuperType = Positioned<SuperType>

data class PrimaryParam(
    val modifiers: List<DecModifier>,
    val decHandling: DecHandling,
    val name: PString,
    val type: PType,
    val default: PExpr?
)

sealed class ClassStmt {
    data class Constructor(
        val modifiers: List<DecModifier>,
        val params: List<FunParam>,
        val primaryCall: List<Arg<PExpr>>?,
        val body: PExprScope?
    ) : ClassStmt()

    data class Initializer(val scope: PExprScope) : ClassStmt()
    data class Method(val function: Function) : ClassStmt()
    data class Property(val property: com.scientianova.palm.parser.data.top.Property) : ClassStmt()
    data class NestedDec(val dec: TypeDec) : ClassStmt()
}

data class ClassTypeParam(val type: PString, val variance: VarianceMod)
typealias PClassTypeParam = Positioned<ClassTypeParam>

typealias TypeConstraints = List<Pair<PString, PType>>