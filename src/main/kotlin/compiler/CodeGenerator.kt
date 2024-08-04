package me.gabriel.gwydion.compiler

import me.gabriel.gwydion.analyzer.SymbolTable
import me.gabriel.gwydion.executor.IntrinsicFunction
import me.gabriel.gwydion.parsing.SyntaxTree

interface CodeGenerator {
    fun generate(tree: SyntaxTree, symbols: SymbolTable): String

    fun registerIntrinsicFunction(function: IntrinsicFunction)
}