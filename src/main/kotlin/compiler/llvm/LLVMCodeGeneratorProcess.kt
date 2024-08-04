package me.gabriel.gwydion.compiler.llvm

import me.gabriel.gwydion.analyzer.SymbolTable
import me.gabriel.gwydion.executor.IntrinsicFunction
import me.gabriel.gwydion.lexing.TokenKind
import me.gabriel.gwydion.parsing.*

class LLVMCodeGeneratorProcess(
    private val tree: SyntaxTree,
    private val symbols: SymbolTable,
    private val intrinsics: List<IntrinsicFunction>
) {
    private val ir = mutableListOf<String>()
    private var labelCounter = 0
    private var registerCounter = 1

    fun setup() {
        intrinsics.forEach { intrinsic ->
            ir.add(intrinsic.llvmIr())
        }
        ir.add("@format = private unnamed_addr constant [3 x i8] c\"%s\\00\"")
    }


    fun generate(tree: SyntaxTree): List<String> {
        ir.clear()
        generateNode(tree.root)
        return ir
    }

    fun generateNode(node: SyntaxTreeNode): Int {
        return when (node) {
            is RootNode -> {
                node.getChildren().forEach { generateNode(it) }
                -1
            }
            is FunctionNode -> generateFunction(node)
            is BlockNode -> {
                node.getChildren().forEach { generateNode(it) }
                -1
            }
            is AssignmentNode -> generateAssignment(node)
            is BinaryOperatorNode -> generateBinaryOperator(node)
            is ReturnNode -> generateReturn(node)
            is CallNode -> generateFunctionCall(node)
            is IfNode -> generateIf(node)
            is StringNode -> generateString(node)
            is VariableReferenceNode -> generateVariableReference(node)
            is NumberNode -> generateNumber(node)
            else -> throw UnsupportedOperationException("Unsupported node type: ${node::class.simpleName}")
        }
    }

    private fun generateFunction(node: FunctionNode): Int {
        val returnType = getLLVMType(node.returnType)
        val paramTypes = node.parameters.map { getLLVMType(it.type) }.joinToString(", ")
        ir.add("define $returnType @${node.name}($paramTypes) {")

        // Allocate parameters
        node.parameters.forEachIndexed { index, param ->
            val reg = getNextRegister()
            ir.add("    %$reg = alloca ${getLLVMType(param.type)}")
            ir.add("    store ${getLLVMType(param.type)} %${index}, ${getLLVMType(param.type)}* %$reg")
        }

        generateNode(node.body)

        if (node.returnType == Type.Void) {
            ir.add("    ret void")
        }

        ir.add("}")
        return -1
    }

    private fun generateAssignment(node: AssignmentNode): Int {
        val valueReg = generateNode(node.expression)
        val allocaReg = getNextRegister()
        val type = getLLVMType(if (node.type == Type.Unknown) {
            symbols.lookup(node.name) ?: throw IllegalStateException("Undefined variable: ${node.name}")
        } else {
            node.type
        })
        ir.add("    %$allocaReg = alloca $type")
        ir.add("    store $type %$valueReg, $type* %$allocaReg")
        return allocaReg
    }

    private fun generateBinaryOperator(node: BinaryOperatorNode): Int {
        val leftReg = generateNode(node.left)
        val rightReg = generateNode(node.right)
        val resultReg = getNextRegister()
        val instruction = when (node.operator) {
            TokenKind.PLUS -> "add"
            TokenKind.MINUS -> "sub"
            TokenKind.TIMES -> "mul"
            TokenKind.DIVIDE -> "sdiv"
            else -> throw UnsupportedOperationException("Unsupported binary operator: ${node.operator}")
        }
        ir.add("    %$resultReg = $instruction i32 %$leftReg, %$rightReg")
        return resultReg
    }

    private fun generateReturn(node: ReturnNode): Int {
        val valueReg = generateNode(node.expression)
        ir.add("    ret i32 %$valueReg")
        return -1
    }

    private fun generateFunctionCall(node: CallNode): Int {
        val argRegs = node.arguments.map { generateNode(it) }
        val resultReg = getNextRegister()
        val argTypes = argRegs.joinToString(", ") { "${getLLVMType(Type.String)} %$it" }
        ir.add("    %$resultReg = call i32 @${node.name}(i8* bitcast ([3 x i8]* @format to i8*), $argTypes)")
        return resultReg
    }

    private fun generateIf(node: IfNode): Int {
        val conditionReg = generateNode(node.condition)
        val thenLabel = getNextLabel("then")
        val elseLabel = getNextLabel("else")
        val endLabel = getNextLabel("endif")

        ir.add("    %cmp = icmp ne i32 %$conditionReg, 0")
        ir.add("    br i1 %cmp, label %$thenLabel, label %$elseLabel")

        ir.add("$thenLabel:")
        generateNode(node.body)
        ir.add("    br label %$endLabel")

        ir.add("$elseLabel:")
        node.elseBody?.let { generateNode(it) }
        ir.add("    br label %$endLabel")

        ir.add("$endLabel:")
        return -1
    }

    private fun generateVariableReference(node: VariableReferenceNode): Int {
        val allocaReg = symbols.lookup(node.name) ?: throw IllegalStateException("Undefined variable: ${node.name}")
        val resultReg = getNextRegister()
        ir.add("    %$resultReg = load i32, i32* %$allocaReg")
        return resultReg
    }

    private fun generateNumber(node: NumberNode): Int {
        val resultReg = getNextRegister()
        ir.add("    %$resultReg = add i32 ${node.value}, 0")
        return resultReg
    }

    private fun generateString(node: StringNode): Int {
        val pointerReg = getNextRegister()
        val length = node.value.length

        // Allocate space for the string
        ir.add("    %$pointerReg = alloca [$length x i8]")

        // Store each character individually
        node.value.forEachIndexed { index, char ->
            val charReg = getNextRegister()
            val ascii = char.code
            ir.add("    %$charReg = getelementptr inbounds [$length x i8], [$length x i8]* %$pointerReg, i64 0, i64 $index")
            ir.add("    store i8 $ascii, i8* %$charReg")
        }

        // Return a pointer to the first character of the string
        val reg = getNextRegister()
        ir.add("    %$reg = getelementptr inbounds [$length x i8], [$length x i8]* %$pointerReg, i64 0, i64 0")

        return reg
    }

    private fun getNextRegister(): Int = registerCounter++

    private fun getNextLabel(prefix: String): String = "${prefix}_${labelCounter++}"

    private fun getLLVMType(type: Type): String {
        return when (type) {
            Type.Int32 -> "i32"
            Type.Boolean -> "i1"
            Type.String -> "i8*"
            Type.Void -> "void"
            else -> throw UnsupportedOperationException("Unsupported type: $type")
        }
    }

    fun finish(): String {
        return ir.joinToString("\n")
    }
}