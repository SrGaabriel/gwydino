package me.gabriel.gwydion.compiler.llvm

import me.gabriel.gwydion.analyzer.getExpressionType
import me.gabriel.gwydion.compiler.MemoryBlock
import me.gabriel.gwydion.compiler.MemoryTable
import me.gabriel.gwydion.compiler.ProgramMemoryRepository
import me.gabriel.gwydion.exception.AnalysisError
import me.gabriel.gwydion.executor.IntrinsicFunction
import me.gabriel.gwydion.lexing.TokenKind
import me.gabriel.gwydion.parsing.*
import me.gabriel.gwydion.util.Either

class LLVMCodeGeneratorProcess(
    private val tree: SyntaxTree,
    private val repository: ProgramMemoryRepository,
    private val intrinsics: List<IntrinsicFunction>
) {
    private val ir = mutableListOf<String>()
    private val dependencies = mutableListOf<String>()
    private var labelCounter = 0
    private var registerCounter = 1

    fun allocate(block: MemoryBlock, name: String, pointer: Int): Int {
        return block.memory.allocate(name, pointer)
    }

    fun lookup(block: MemoryBlock, name: String): Int? {
        return block.figureOutMemory(name)
    }

    fun setup() {
        dependencies.addAll(intrinsics.flatMap { it.dependencies() })
        ir.add("declare i32 @strcmp(i8*, i8*)")
        ir.add("declare i32 @strlen(i8*)")
        ir.add("declare i8* @malloc(i32)")
        ir.add("declare void @memcpy(i8*, i8*, i32)")
        ir.add("@format_s = private unnamed_addr constant [3 x i8] c\"%s\\00\"")
        ir.add("@format_n = private unnamed_addr constant [3 x i8] c\"%d\\00\"")
        dependencies.forEach {
            ir.add(it)
        }
        intrinsics.forEach { intrinsic ->
            ir.add(intrinsic.llvmIr())
            val symbols = repository.root.symbols
            symbols.declare(intrinsic.name, intrinsic.returnType)
            intrinsic.params.forEachIndexed { index, type ->
                symbols.declare("param$index", type)
            }
        }
    }

    fun generateNode(node: SyntaxTreeNode, block: MemoryBlock): Int {
        return when (node) {
            is RootNode -> {
                node.getChildren().forEach { generateNode(it, block) }
                -1
            }
            is FunctionNode -> generateFunction(node)
            is BlockNode -> {
                node.getChildren().forEach { generateNode(it, block) }
                -1
            }
            is AssignmentNode -> generateAssignment(block, node)
            is BinaryOperatorNode -> generateBinaryOperator(block, node)
            is ReturnNode -> generateReturn(block, node)
            is CallNode -> generateFunctionCall(block, node)
            is IfNode -> generateIf(block, node)
            is BooleanNode -> generateBoolean(block, node)
            is StringNode -> generateString(block, node)
            is VariableReferenceNode -> generateVariableReference(block, node)
            is EqualsNode -> generateEqualsComparison(block, node)
            is NumberNode -> generateNumber(block, node)
            else -> throw UnsupportedOperationException("Unsupported node type: ${node::class.simpleName}")
        }
    }

    fun generateBoolean(block: MemoryBlock, node: BooleanNode): Int {
        val resultReg = block.getNextRegister()
        ir.add("    %$resultReg = add i1 ${node.value}, 0")
        return resultReg
    }

    private fun generateFunction(node: FunctionNode): Int {
        if (node.modifiers.contains(Modifiers.INTRINSIC)) return -1
        val block = repository.root.surfaceSearchChild(node.name) ?: throw IllegalStateException("Undefined function: ${node.name}")
        val returnType = getLLVMType(node.returnType)
        val paramTypes = node.parameters.joinToString(", ") {
            val register = block.getNextRegister()
            block.memory.allocate(it.name, register)
            getLLVMType(it.type) + " %$register"
        }
        ir.add("define $returnType @${node.name}($paramTypes) {")
        ir.add("entry:")

        generateNode(node.body, block)

        if (node.returnType == Type.Void) {
            ir.add("    ret void")
        }

        ir.add("}")
        return -1
    }

    private fun generateAssignment(block: MemoryBlock, node: AssignmentNode): Int {
        val valueReg = generateNode(node.expression, block)
        val allocaReg = block.getNextRegister()
        val type = getLLVMType(if (node.type == Type.Unknown) {
            getExpressionType(block, node).getRightOrNull() ?: error("Unknown type")
        } else {
            node.type
        })
        ir.add("    %$allocaReg = alloca $type")
        ir.add("    store $type %$valueReg, $type* %$allocaReg")
        allocate(block, node.name, valueReg)
        return valueReg
    }

    private fun generateBinaryOperator(block: MemoryBlock, node: BinaryOperatorNode): Int {
        val leftReg = generateNode(node.left, block)
        val rightReg = generateNode(node.right, block)
        val typeResult = getExpressionType(block, node.left)
        if (typeResult.isLeft()) {
            throw IllegalStateException("Unknown type for binary operator")
        }
        val type = typeResult.unwrap()
        val resultReg = if (type == Type.String) {
            val leftLengthReg = block.getNextRegister()
            ir.add("    %$leftLengthReg = call i32 @strlen(i8* %$leftReg)")
            val rightLengthReg = block.getNextRegister()
            ir.add("    %$rightLengthReg = call i32 @strlen(i8* %$rightReg)")

            val totalLengthWithoutNullReg = block.getNextRegister()
            ir.add("    %$totalLengthWithoutNullReg = add i32 %$leftLengthReg, %$rightLengthReg")
            val totalLengthReg = block.getNextRegister()
            ir.add("    %$totalLengthReg = add i32 %$totalLengthWithoutNullReg, 1")

            val resultReg = block.getNextRegister()
            ir.add("    %$resultReg = call i8* @malloc(i32 %$totalLengthReg)")
            ir.add("    call void @memcpy(i8* %$resultReg, i8* %$leftReg, i32 %$totalLengthReg)")
            val resultOffsetReg = block.getNextRegister()
            ir.add("    %$resultOffsetReg = getelementptr inbounds i8, i8* %$resultReg, i32 %$leftLengthReg")
            ir.add("    call void @memcpy(i8* %$resultOffsetReg, i8* %$rightReg, i32 %$totalLengthReg)")
            return resultReg
        } else block.getNextRegister()

        val instruction = when (node.operator) {
            TokenKind.PLUS -> "add"
            TokenKind.MINUS -> "sub"
            TokenKind.TIMES -> "mul"
            TokenKind.DIVIDE -> "sdiv"
            else -> throw UnsupportedOperationException("Unsupported binary operator: ${node.operator}")
        }
        ir.add("    %$resultReg = $instruction ${getLLVMType(type)} %$leftReg, %$rightReg")
        return resultReg
    }

    private fun generateReturn(block: MemoryBlock, node: ReturnNode): Int {
        val valueReg = generateNode(node.expression, block)
        val type = getExpressionType(block, node.expression)
        if (type.isLeft()) {
            throw IllegalStateException("Unknown type for return value")
        }
        ir.add("    ret ${getLLVMType(type.unwrap())} %$valueReg")
        return -1
    }

    private fun generateFunctionCall(block: MemoryBlock, node: CallNode): Int {
        val argRegs = node.arguments.mapIndexed { index, syntaxTreeNode -> getExpressionType(block, syntaxTreeNode) to generateNode(syntaxTreeNode, block) }
        val functionType = block.figureOutSymbol(node.name) ?: throw IllegalStateException("Undefined function: ${node.name}")
        val resultReg = block.getNextRegister()
        var initialType: Type = Type.Unknown
        val argTypes = argRegs.joinToString(", ") {
            val (type, argReg) = it
            if (type.isLeft()) {
                throw IllegalStateException("Unknown type for argument")
            }
            if (initialType == Type.Unknown) {
                initialType = type.unwrap()
            }
            "${getLLVMType(type.unwrap())} %$argReg"
        }

        val intrinsic = intrinsics.find { it.name == node.name }
        val call = if (intrinsic != null) {
            intrinsic.handleCall(
                node,
                node.arguments.map { getExpressionType(block, it).unwrap() },
                argTypes
            )
        } else {
            "call ${getLLVMType(functionType)} @${node.name}($argTypes)"
        }

        if (functionType !== Type.Void) {
            ir.add("    %$resultReg = $call")
        } else {
            ir.add("    $call")
        }
        return resultReg
    }

    private fun generateIf(block: MemoryBlock, node: IfNode): Int {
        val conditionReg = generateNode(node.condition, block)
        val thenLabel = getNextLabel("then")
        val elseLabel = getNextLabel("else")
        val endLabel = getNextLabel("endif")

        ir.add("    %cmp = icmp ne i1 %$conditionReg, 0")
        ir.add("    br i1 %cmp, label %$thenLabel, label %$elseLabel")

        ir.add("$thenLabel:")
        generateNode(node.body, block)
        ir.add("    br label %$endLabel")

        ir.add("$elseLabel:")
        node.elseBody?.let { generateNode(it, block) }
        ir.add("    br label %$endLabel")

        ir.add("$endLabel:")
        return -1
    }

    private fun generateVariableReference(block: MemoryBlock, node: VariableReferenceNode): Int {
        val register = lookup(block, node.name)
        if (register == null) {
            val resultReg = block.getNextRegister()
            ir.add("    %$resultReg = load i32, i32* %$register")
            return resultReg
        }
        return register
    }

    private fun generateNumber(block: MemoryBlock, node: NumberNode): Int {
        val resultReg = block.getNextRegister()
        ir.add("    %$resultReg = add i32 ${node.value}, 0")
        block.memory.allocate(node.value, resultReg)
        return resultReg
    }

    private fun generateString(block: MemoryBlock, node: StringNode): Int {
        val pointerReg = block.getNextRegister()
        val length = node.value.length+1

        val isFlat = node.segments.all { it is StringNode.Segment.Text }
        if (isFlat) {
            val text = node.segments.joinToString("") { (it as StringNode.Segment.Text).text }
            println(text)
            ir.add("    %$pointerReg = alloca [$length x i8]")

            var firstPointer: Int? = null
            text.forEachIndexed { index, char ->
                val charReg = block.getNextRegister()
                if (firstPointer == null) {
                    firstPointer = charReg
                }
                val ascii = char.code
                ir.add("    %$charReg = getelementptr inbounds [$length x i8], [$length x i8]* %$pointerReg, i32 0, i32 $index")
                ir.add("    store i8 $ascii, i8* %$charReg")
            }
            // null-terminate the string
            val nullReg = block.getNextRegister()
            ir.add("    %$nullReg = getelementptr inbounds [$length x i8], [$length x i8]* %$pointerReg, i32 0, i32 ${text.length}")
            ir.add("    store i8 0, i8* %$nullReg")
            if (firstPointer == null) {
                firstPointer = nullReg
            }
            block.memory.allocate(text, firstPointer!!)
            return firstPointer!!
        }
        val segmentRegs = mutableListOf<Triple<Int, Int, Boolean>>()
        var totalLengthReg = block.getNextRegister()
        ir.add("    %$totalLengthReg = add i32 1, 0")

        node.segments.forEach { segment ->
            when (segment) {
                is StringNode.Segment.Text -> {
                    val textReg = block.getNextRegister()
                    val length = segment.text.length + 1
                    ir.add("    %$textReg = alloca [$length x i8]")
                    segment.text.forEachIndexed { index, char ->
                        val charReg = block.getNextRegister()
                        val ascii = char.code
                        ir.add("    %$charReg = getelementptr inbounds [$length x i8], [$length x i8]* %$textReg, i32 0, i32 $index")
                        ir.add("    store i8 $ascii, i8* %$charReg")
                    }
                    ir.add("    %${block.getNextRegister()} = getelementptr inbounds [$length x i8], [$length x i8]* %$textReg, i32 0, i32 ${segment.text.length}")
                    ir.add("    store i8 0, i8* %${block.getNextRegister() - 1}")
                    segmentRegs.add(Triple(textReg, length - 1, false))
                    ir.add("    %${block.getNextRegister()} = add i32 %$totalLengthReg, ${length - 1}")
                    totalLengthReg = block.getNextRegister() - 1
                }
                is StringNode.Segment.Reference -> {
                    val refReg = generateVariableReference(block, segment.node)
                    val lengthReg = block.getNextRegister()
                    ir.add("    %$lengthReg = call i32 @strlen(i8* %$refReg)")
                    segmentRegs.add(Triple(refReg, lengthReg, true))
                    ir.add("    %${block.getNextRegister()} = add i32 %$totalLengthReg, %$lengthReg")
                    totalLengthReg = block.getNextRegister() - 1
                }
                is StringNode.Segment.Expression -> {
                    error("String interpolation not yet implemented")
                }
            }
        }

        val finalReg = block.getNextRegister()
        ir.add("    %$finalReg = call i8* @malloc(i32 %$totalLengthReg)")

        var offsetReg = block.getNextRegister()
        ir.add("    %$offsetReg = add i32 0, 0")
        segmentRegs.forEach { (valueReg, lengthReg, isDynamic) ->
            val destReg = block.getNextRegister()
            ir.add("    %$destReg = getelementptr inbounds i8, i8* %$finalReg, i32 %$offsetReg")
            if (isDynamic) {
                ir.add("    call void @memcpy(i8* %$destReg, i8* %$valueReg, i32 %$lengthReg)")
                val newOffsetReg = block.getNextRegister()
                ir.add("    %$newOffsetReg = add i32 %$offsetReg, %$lengthReg")
                offsetReg = newOffsetReg
            } else {
                ir.add("    call void @memcpy(i8* %$destReg, i8* %$valueReg, i32 $lengthReg)")
                val newOffsetReg = block.getNextRegister()
                ir.add("    %$newOffsetReg = add i32 %$offsetReg, $lengthReg")
                offsetReg = newOffsetReg
            }
        }

        val nullTermReg = block.getNextRegister()
        ir.add("    %$nullTermReg = getelementptr inbounds i8, i8* %$finalReg, i32 %$offsetReg")
        ir.add("    store i8 0, i8* %$nullTermReg")

        return finalReg
    }

    fun generateEqualsComparison(block: MemoryBlock, node: EqualsNode): Int {
        val leftReg = generateNode(node.left, block)
        val rightReg = generateNode(node.right, block)
        val typeResult = getExpressionType(block, node.left)
        if (typeResult.isLeft()) {
            throw IllegalStateException("Unknown type for comparison")
        }
        val type = typeResult.unwrap()
        val resultReg = if (type != Type.String) {
            val resultReg = block.getNextRegister()
            ir.add("    %$resultReg = icmp eq ${getLLVMType(type)} %$leftReg, %$rightReg")
            resultReg
        } else {
            val callReg = block.getNextRegister()
            ir.add("    %$callReg = call i1 @strcmp(i8* %$leftReg, i8* %$rightReg)")
            // let's add calls to println for debugging purposes
            val resultReg = block.getNextRegister()
            ir.add("    %$resultReg = icmp eq i1 %$callReg, 0")
            resultReg
        }
        return resultReg
    }

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