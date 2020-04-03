package com.scientianova.palm.evaluator

import com.scientianova.palm.registry.IPalmType
import com.scientianova.palm.registry.TypeRegistry
import kotlin.reflect.KClass

infix fun Any?.instanceOf(clazz: Class<*>) =
    this == null || clazz.isInstance(this) || this::class.javaPrimitiveType == clazz

val Class<out Any>.palm: IPalmType get() = TypeRegistry.getOrRegister(this)
val KClass<out Any>.palm get() = java.palm

val Any?.palmType get() = TypeRegistry.getOrRegister(this?.javaClass ?: Any::class.java)