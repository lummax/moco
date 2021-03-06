/*
 * moco, the Monty Compiler
 * Copyright (c) 2013-2014, Monty's Coconut, All rights reserved.
 *
 * This file is part of moco, the Monty Compiler.
 *
 * moco is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * moco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * Linking this program and/or its accompanying libraries statically or
 * dynamically with other modules is making a combined work based on this
 * program. Thus, the terms and conditions of the GNU General Public License
 * cover the whole combination.
 *
 * As a special exception, the copyright holders of moco give
 * you permission to link this programm and/or its accompanying libraries
 * with independent modules to produce an executable, regardless of the
 * license terms of these independent modules, and to copy and distribute the
 * resulting executable under terms of your choice, provided that you also meet,
 * for each linked independent module, the terms and conditions of the
 * license of that module.
 *
 * An independent module is a module which is not
 * derived from or based on this program and/or its accompanying libraries.
 * If you modify this library, you may extend this exception to your version of
 * the program or library, but you are not obliged to do so. If you do not wish
 * to do so, delete this exception statement from your version.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library.
 */
package de.uni.bremen.monty.moco.visitor;

import de.uni.bremen.monty.moco.ast.*;
import de.uni.bremen.monty.moco.ast.Package;
import de.uni.bremen.monty.moco.ast.declaration.*;
import de.uni.bremen.monty.moco.ast.expression.*;
import de.uni.bremen.monty.moco.ast.expression.literal.*;
import de.uni.bremen.monty.moco.ast.statement.*;
import de.uni.bremen.monty.moco.codegeneration.CodeGenerator;
import de.uni.bremen.monty.moco.codegeneration.NameMangler;
import de.uni.bremen.monty.moco.codegeneration.context.CodeContext;
import de.uni.bremen.monty.moco.codegeneration.context.ContextUtils;
import de.uni.bremen.monty.moco.codegeneration.identifier.LLVMIdentifier;
import de.uni.bremen.monty.moco.codegeneration.identifier.LLVMIdentifierFactory;
import de.uni.bremen.monty.moco.codegeneration.types.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Stack;

import static de.uni.bremen.monty.moco.codegeneration.types.LLVMTypeFactory.pointer;

/** The CodeGenerationVisitor has the following tasks:
 *
 * <p>
 * <ul>
 * <li>Process the AST</li>
 * <li>Delegates as much work as possible to the CodeGenerator</li>
 * <li>Tell the CodeGenerator in which {@link CodeContext} to write</li>
 * <li>Evaluated expression should be given Statements as Arguments, see {@link #stack}</li>
 * </ul>
 * </p> */
public class CodeGenerationVisitor extends BaseVisitor {

	private final LLVMIdentifierFactory llvmIdentifierFactory = new LLVMIdentifierFactory();
	private ContextUtils contextUtils = new ContextUtils();
	private final CodeGenerator codeGenerator;
	private final NameMangler nameMangler;

	/** Each Expression pushes it's evaluated value onto the Stack. The value is represented by a LLVMIdentifier where
	 * the evaluated value is stored at runtime.
	 *
	 * Statements or complex Expressions can pop those values from the stack, which they use as parameters for further
	 * calculation.
	 *
	 * e.g. a := 3 is an Assignment having a VariableAccess and IntLiteral as children. VariableAccess and IntLiteral
	 * are expressions, thus pushing their values on the stack. An Assignment on the other hand is an Statement and
	 * return nothing, so doesn't push sth. on the stack, but instead it needs two Arguments. Those are popped from the
	 * Stack and yield the the evaluated VariableAccess and IntLiteral.
	 *
	 * Of course this only works, if the Assignment first process the children and afterwards popping from the stack. */
	private Stack<LLVMIdentifier<LLVMType>> stack = new Stack<>();

	public CodeGenerationVisitor() {
		nameMangler = new NameMangler();
		TypeConverter typeConverter = new TypeConverter(llvmIdentifierFactory, contextUtils.constant(), nameMangler);
		nameMangler.setTypeConverter(typeConverter);
		this.codeGenerator = new CodeGenerator(typeConverter, llvmIdentifierFactory, nameMangler);
	}

	public void writeLLVMCode(StringBuffer llvmOutput) {
		contextUtils.writeLLVMCode(llvmOutput);
	}

	private void openNewFunctionScope() {
		contextUtils.addNewContext();
		llvmIdentifierFactory.openScope();
	}

	private void closeFunctionContext() {
		contextUtils.active().close();
		contextUtils.closeContext();
		llvmIdentifierFactory.closeScope();
	}

	private List<LLVMIdentifier<? extends LLVMType>> buildLLVMParameter(FunctionDeclaration node) {
		List<LLVMIdentifier<? extends LLVMType>> llvmParameter = new ArrayList<>();

		if (node.isMethod() || node.isInitializer()) {
			ClassDeclaration typeDeclaration =
			        (ClassDeclaration) codeGenerator.mapAbstractGenericToConcreteIfApplicable(node.getDefiningClass());
			LLVMType selfType = codeGenerator.mapToLLVMType(typeDeclaration);
			LLVMIdentifier<LLVMType> selfReference = llvmIdentifierFactory.newLocal("self", selfType, false);
			llvmParameter.add(selfReference);
		}

		for (VariableDeclaration param : node.getParameters()) {
			LLVMType llvmType = codeGenerator.mapToLLVMType(param.getType());
			llvmType = llvmType instanceof LLVMStructType ? pointer(llvmType) : llvmType;
			boolean resolvable = llvmType instanceof LLVMStructType;
			LLVMIdentifier<LLVMType> e =
			        llvmIdentifierFactory.newLocal(nameMangler.mangleVariable(param), llvmType, resolvable);

			llvmParameter.add(e);
		}
		return llvmParameter;
	}

	private void addFunction(FunctionDeclaration node, TypeDeclaration returnType) {
		List<LLVMIdentifier<? extends LLVMType>> llvmParameter = buildLLVMParameter(node);
		String name = nameMangler.mangleFunction(node);
		codeGenerator.addFunction(contextUtils.active(), returnType, llvmParameter, name);

		if (node instanceof GeneratorFunctionDeclaration) {
			addGeneratorJumpHeader((GeneratorFunctionDeclaration) node);
		}
	}

	private void addGeneratorJumpHeader(GeneratorFunctionDeclaration node) {
		TypeDeclaration selfType = useClassVariationIfApplicable(node.getDefiningClass());
		LLVMIdentifier<LLVMPointer<LLVMType>> self = codeGenerator.resolveLocalVarName("self", selfType, false);

		LLVMIdentifier<LLVMType> pointervar =
		        codeGenerator.accessGeneratorJumpPointer(contextUtils.active(), self, node.getDefiningClass(), 0, false); // no
		                                                                                                                  // LValue

		// get the actual attribute
		LLVMType jumpPrtType = LLVMTypeFactory.pointer(LLVMTypeFactory.int8());
		LLVMIdentifier<LLVMType> pointercontentvar = llvmIdentifierFactory.newLocal(jumpPrtType, false);
		contextUtils.active().load(llvmIdentifierFactory.pointerTo(pointervar), pointercontentvar);

		// get all possible target labels:
		String labels = "label %startGenerator";
		for (int i = 0; i < node.getYieldStatements().size(); i++) {
			labels += ", label %yield" + i;
		}
		// jump to the label which is stored in that attribute
		contextUtils.active().appendLine("indirectbr " + pointercontentvar + ", [ " + labels + " ]");
		contextUtils.active().label("startGenerator");
	}

	private void setGeneratorLabel(ClassDeclaration classDecl, String label) {
		TypeDeclaration selfType = useClassVariationIfApplicable(classDecl);
		LLVMIdentifier<LLVMPointer<LLVMType>> self = codeGenerator.resolveLocalVarName("self", selfType, false);

		LLVMIdentifier<LLVMType> pointervar =
		        codeGenerator.accessGeneratorJumpPointer(contextUtils.active(), self, classDecl, 0, true); // used as
		                                                                                                   // lvalue
		FunctionDeclaration inFunction = null;
		for (FunctionDeclaration fun : classDecl.getMethods()) {
			if (fun.getIdentifier().getSymbol().equals("getNext")) {
				inFunction = fun;
				break;
			}
		}

		String funName = nameMangler.mangleFunction(inFunction);
		contextUtils.active().appendLine(
		        "store i8* blockaddress(@" + funName + ", %" + label + "), "
		                + llvmIdentifierFactory.pointerTo(pointervar));
	}

	private void addNativeFunction(FunctionDeclaration node, TypeDeclaration returnType) {
		List<LLVMIdentifier<? extends LLVMType>> llvmParameter = buildLLVMParameter(node);
		String name = nameMangler.mangleFunction(node);
		codeGenerator.addNativeFunction(contextUtils.active(), returnType, llvmParameter, name);
	}

	private boolean isNative(ASTNode node) {
		if ((node instanceof FunctionDeclaration) && (((FunctionDeclaration) node).isAbstract())) {
			return false; // abstract methods are never native
		}
		while (node.getParentNode() != null) {
			node = node.getParentNode();
			if (node instanceof ModuleDeclaration) {
				if (!((ModuleDeclaration) node).isNative()) {
					return false;
				}
			}
			if (node instanceof Package) {
				if (((Package) node).isNativePackage()) {
					return true;
				}
			} else if ((node instanceof ClassDeclaration) && (((ClassDeclaration) node).isFunctionWrapper())) {
				return false; // function wrappers are never native,
				              // because they are always automatically generated monty code
			}
		}
		return false;
	}

	@Override
	public void visit(Package node) {
		if (node.getParentNode() == null) {
			openNewFunctionScope();
			codeGenerator.addMain(contextUtils.active());

			super.visit(node);

			codeGenerator.returnMain(contextUtils.active());
			closeFunctionContext();
		} else {
			super.visit(node);
		}
	}

	@Override
	public void visit(Block node) {
		for (Declaration declaration : node.getDeclarations()) {
			visitDoubleDispatched(declaration);
		}
		for (Statement statement : node.getStatements()) {
			stack.clear();
			visitDoubleDispatched(statement);
		}
	}

	@Override
	public void visit(Assignment node) {
		super.visit(node);
		LLVMIdentifier<LLVMType> source = stack.pop();
		LLVMIdentifier<LLVMType> target = stack.pop();
		codeGenerator.assign(contextUtils.active(), target, source);
	}

	@Override
	public void visit(ClassDeclaration node) {
		if (node instanceof ClassDeclarationVariation) {
			codeGenerator.pushClassDeclarationVariation((ClassDeclarationVariation) node);
		}
		onEnterChildrenEachNode(node);
		if (node.getAbstractGenericTypes().isEmpty() || node instanceof ClassDeclarationVariation) {
			if (node != CoreClasses.voidType()) {
				openNewFunctionScope();
				codeGenerator.buildConstructor(contextUtils.active(), node);
				closeFunctionContext();
			}
			visitDoubleDispatched(node.getBlock());
		} else {
			for (ClassDeclarationVariation variation : node.getVariations()) {
				visit(variation);
			}
		}
		onExitChildrenEachNode(node);
		if (node instanceof ClassDeclarationVariation) {
			codeGenerator.popClassDeclarationVariation();
		}
	}

	@Override
	public void visit(VariableDeclaration node) {
		super.visit(node);
		if (!node.isAttribute()) {
			if (node.getIsGlobal()) {
				codeGenerator.declareGlobalVariable(
				        contextUtils.constant(),
				        nameMangler.mangleVariable(node),
				        node.getType());
			} else if (!(node.getParentNodeByType(FunctionDeclaration.class) instanceof GeneratorFunctionDeclaration)) {
				// only do sth. if the enclosing function is not a generator function.
				// if it is, the declaration is already made somewhere else...
				codeGenerator.declareLocalVariable(
				        contextUtils.active(),
				        nameMangler.mangleVariable(node),
				        node.getType());
			}
		}
	}

	@Override
	public void visit(VariableAccess node) {
		super.visit(node);

		VariableDeclaration varDeclaration = (VariableDeclaration) node.getDeclaration();

		TypeDeclaration type = node.getType();
		if (type instanceof ClassDeclaration) {
			type = codeGenerator.mapAbstractGenericToConcreteIfApplicable((ClassDeclaration) type);
		}

		FunctionDeclaration functionParent =
		        (FunctionDeclaration) varDeclaration.getParentNodeByType(FunctionDeclaration.class);
		GeneratorFunctionDeclaration generatorParent = null;
		if (functionParent instanceof GeneratorFunctionDeclaration) {
			generatorParent =
			        (GeneratorFunctionDeclaration) varDeclaration.getParentNodeByType(GeneratorFunctionDeclaration.class);
		}

		LLVMIdentifier<LLVMType> llvmIdentifier;
		if (varDeclaration.getIsGlobal()) {
			llvmIdentifier = codeGenerator.resolveGlobalVarName(nameMangler.mangleVariable(varDeclaration), type);
		} else if (generatorParent != null) {
			llvmIdentifier =
			        codeGenerator.accessContextMember(
			                contextUtils.active(),
			                (ClassDeclaration) useClassVariationIfApplicable(generatorParent.getDefiningClass()),
			                varDeclaration,
			                node,
			                type);

		} else if (varDeclaration.isAttribute()) {
			LLVMIdentifier<?> leftIdentifier = stack.pop();
			llvmIdentifier =
			        codeGenerator.accessMember(
			                contextUtils.active(),
			                (LLVMIdentifier<LLVMPointer<LLVMType>>) leftIdentifier,
			                varDeclaration.getAttributeIndex(),
			                type,
			                !node.getLValue());
		} else {
			llvmIdentifier =
			        codeGenerator.resolveLocalVarName(
			                nameMangler.mangleVariable(varDeclaration),
			                type,
			                !varDeclaration.isParameter());
		}
		stack.push(llvmIdentifier);
	}

	/** this method finds out whether the given class is a generic class. If yes, it is replaced by its equivalent
	 * ClassDeclarationVariation.
	 *
	 * @param type
	 * @return */
	private TypeDeclaration useClassVariationIfApplicable(TypeDeclaration type) {
		if ((!(type instanceof ClassDeclarationVariation)) && (type instanceof ClassDeclaration)
		        && (!((ClassDeclaration) type).getAbstractGenericTypes().isEmpty())) {
			return codeGenerator.mapAbstractGenericToConcreteIfApplicable((ClassDeclaration) type);
		}
		return type;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void visit(SelfExpression node) {
		TypeDeclaration type = useClassVariationIfApplicable(node.getType());
		stack.push(codeGenerator.resolveLocalVarName("self", type, false));
	}

	@SuppressWarnings("unchecked")
	@Override
	public void visit(ParentExpression node) {
		LLVMIdentifier<?> self = codeGenerator.resolveLocalVarName("self", node.getSelfType(), false);
		LLVMIdentifier<?> result =
		        codeGenerator.castClass(
		                contextUtils.active(),
		                (LLVMIdentifier<LLVMPointer<LLVMType>>) self,
		                node.getSelfType(),
		                (ClassDeclaration) node.getType(),
		                codeGenerator.createLabelPrefix("cast", node));
		stack.push((LLVMIdentifier<LLVMType>) result);
	}

	/** {@inheritDoc} */
	@Override
	public void visit(CastExpression node) {
		super.visit(node);
		LLVMIdentifier<?> object = stack.pop();
		LLVMIdentifier<?> result =
		        codeGenerator.castClass(
		                contextUtils.active(),
		                (LLVMIdentifier<LLVMPointer<LLVMType>>) object,
		                (ClassDeclaration) node.getExpression().getType(),
		                (ClassDeclaration) node.getType(),
		                codeGenerator.createLabelPrefix("cast", node));
		stack.push((LLVMIdentifier<LLVMType>) result);
	}

	/** {@inheritDoc} */
	@Override
	public void visit(IsExpression node) {
		super.visit(node);
		LLVMIdentifier<?> object = stack.pop();
		LLVMIdentifier<?> result =
		        codeGenerator.isClass(
		                contextUtils.active(),
		                (LLVMIdentifier<LLVMPointer<LLVMType>>) object,
		                (ClassDeclaration) node.getExpression().getType(),
		                (ClassDeclaration) node.getToType());
		LLVMIdentifier<LLVMType> boxedResult =
		        codeGenerator.boxType(contextUtils.active(), (LLVMIdentifier<LLVMType>) result, CoreClasses.boolType());
		stack.push(boxedResult);
	}

	@Override
	public void visit(MemberAccess node) {
		super.visit(node);
		// If right is VariableAccess, everything is done in visit(VariableAccess)
		// If right is FunctionCall, everything is done in visit(FunctionCall)
	}

	@Override
	public void visit(ZeroExpression node) {
		super.visit(node);
		stack.push(llvmIdentifierFactory.constantNull((LLVMPointer) codeGenerator.mapToLLVMType(node.getType())));
	}

	@SuppressWarnings("unchecked")
	@Override
	public void visit(StringLiteral node) {
		super.visit(node);
		LLVMIdentifier<? extends LLVMType> addr =
		        codeGenerator.addConstantString(contextUtils.constant(), node.getValue());
		// Boxing
		CodeContext c = contextUtils.active();
		LLVMIdentifier<LLVMType> box = codeGenerator.boxType(c, (LLVMIdentifier<LLVMType>) addr, node.getType());
		stack.push(box);
	}

	@SuppressWarnings("unchecked")
	@Override
	public void visit(CharacterLiteral node) {
		super.visit(node);
		LLVMIdentifier<? extends LLVMType> addr = codeGenerator.loadChar(node.getValue());
		// Boxing
		CodeContext c = contextUtils.active();
		LLVMIdentifier<LLVMType> box = codeGenerator.boxType(c, (LLVMIdentifier<LLVMType>) addr, node.getType());
		stack.push(box);
	}

	@SuppressWarnings("unchecked")
	@Override
	public void visit(IntegerLiteral node) {
		super.visit(node);

		LLVMIdentifier<? extends LLVMType> addr = codeGenerator.loadInt(node.getValue());
		// Boxing
		CodeContext c = contextUtils.active();
		LLVMIdentifier<LLVMType> box = codeGenerator.boxType(c, (LLVMIdentifier<LLVMType>) addr, node.getType());
		stack.push(box);
	}

	@SuppressWarnings("unchecked")
	@Override
	public void visit(BooleanLiteral node) {
		super.visit(node);

		LLVMIdentifier<? extends LLVMType> addr = codeGenerator.loadBool(node.getValue());
		// Boxing
		CodeContext c = contextUtils.active();
		LLVMIdentifier<LLVMType> box = codeGenerator.boxType(c, (LLVMIdentifier<LLVMType>) addr, node.getType());
		stack.push(box);
	}

	@SuppressWarnings("unchecked")
	@Override
	public void visit(FloatLiteral node) {
		super.visit(node);

		LLVMIdentifier<? extends LLVMType> addr = codeGenerator.loadFloat(node.getValue());
		// Boxing
		CodeContext c = contextUtils.active();
		LLVMIdentifier<LLVMType> box = codeGenerator.boxType(c, (LLVMIdentifier<LLVMType>) addr, node.getType());
		stack.push(box);
	}

	@Override
	public void visit(ArrayLiteral node) {
		super.visit(node);

		List<LLVMIdentifier<?>> elements = new ArrayList<>(node.getEntries().size());
		for (int i = 0; i < node.getEntries().size(); i++) {
			elements.add(stack.pop());
		}
		Collections.reverse(elements);

		LLVMIdentifier<? extends LLVMType> array = codeGenerator.buildArray(contextUtils.active(), elements);

		// Boxing
		CodeContext c = contextUtils.active();
		LLVMIdentifier<LLVMType> box = codeGenerator.boxType(c, (LLVMIdentifier<LLVMType>) array, node.getType());
		stack.push(box);
	}

	@Override
	public void visit(ConditionalExpression node) {

		String ifPre = codeGenerator.createLabelPrefix("ifexpr", node);
		String ifTrue = ifPre + ".true";
		String ifFalse = ifPre + ".false";
		String ifEnd = ifPre + ".end";

		visitDoubleDispatched(node.getCondition());

		LLVMIdentifier<LLVMType> condition = stack.pop();
		codeGenerator.branch(contextUtils.active(), condition, ifTrue, ifFalse);

		contextUtils.active().label(ifTrue);
		visitDoubleDispatched(node.getThenExpression());
		LLVMIdentifier<LLVMType> thenExpr = stack.pop();
		contextUtils.active().branch(ifEnd);

		contextUtils.active().label(ifFalse);
		visitDoubleDispatched(node.getElseExpression());
		LLVMIdentifier<LLVMType> elseExpr = stack.pop();
		contextUtils.active().branch(ifEnd);

		contextUtils.active().label(ifEnd);
		List<LLVMIdentifier<LLVMType>> identifiers = new ArrayList<>();
		identifiers.add(thenExpr);
		identifiers.add(elseExpr);
		List<String> labels = new ArrayList<>();
		labels.add(ifTrue);
		labels.add(ifFalse);
		stack.push(contextUtils.active().phi(
		        thenExpr.getType(),
		        thenExpr.needToBeResolved(),
		        identifiers,
		        llvmIdentifierFactory.newLocal(thenExpr.getType(), thenExpr.needToBeResolved()),
		        labels));
	}

	@SuppressWarnings("unchecked")
	@Override
	public void visit(FunctionCall node) {
		super.visit(node);

		List<TypeDeclaration> expectedParameters = new ArrayList<>();
		for (VariableDeclaration varDeclaration : node.getDeclaration().getParameters()) {
			expectedParameters.add(varDeclaration.getType());
		}
		List<LLVMIdentifier<?>> arguments = new ArrayList<>(node.getArguments().size());
		for (int i = 0; i < node.getArguments().size(); i++) {
			arguments.add(stack.pop());
		}
		Collections.reverse(arguments);

		FunctionDeclaration declaration = node.getDeclaration();

		ClassDeclaration definingClass =
		        (ClassDeclaration) useClassVariationIfApplicable(declaration.getDefiningClass());

		List<ClassDeclaration> treatSpecial =
		        Arrays.asList(
		                CoreClasses.intType(),
		                CoreClasses.boolType(),
		                CoreClasses.floatType(),
		                CoreClasses.charType(),
		                CoreClasses.stringType(),
		                CoreClasses.arrayType());
		if (declaration.isInitializer() && treatSpecial.contains(definingClass)) {
			// Instead of calling the initializer of this boxed type with a boxed value as arguments just push the
			// argument on the stack and return.
			stack.push((LLVMIdentifier<LLVMType>) arguments.get(0));
			return;
		}

		if (declaration.isMethod() || declaration.isInitializer()) {
			expectedParameters.add(0, definingClass);
			if (declaration.isMethod()) {
				arguments.add(0, stack.pop());
			} else if (declaration.isInitializer()) {
				ASTNode parentNode = node.getParentNode();
				ASTNode rightMember = node;
				if (parentNode instanceof WrappedFunctionCall) {
					rightMember = parentNode;
					parentNode = parentNode.getParentNode();
				}
				if ((parentNode instanceof MemberAccess) && (((MemberAccess) parentNode).getRight() == rightMember)) {
					arguments.add(0, stack.pop());
				} else {
					LLVMIdentifier<LLVMType> selfReference =
					        codeGenerator.callConstructor(contextUtils.active(), definingClass);
					if (!declaration.isDefaultInitializer()) {
						codeGenerator.callVoid(
						        contextUtils.active(),
						        nameMangler.mangleFunction(definingClass.getDefaultInitializer()),
						        Arrays.<LLVMIdentifier<?>> asList(selfReference),
						        Arrays.<TypeDeclaration> asList(definingClass));
					}
					arguments.add(0, selfReference);
				}
			}
		}

		if (declaration.isMethod() && !declaration.isInitializer()) {
			if (declaration.isFunction()) {
				stack.push((LLVMIdentifier<LLVMType>) codeGenerator.callMethod(
				        contextUtils.active(),
				        declaration,
				        arguments,
				        expectedParameters));
			} else {
				codeGenerator.callVoidMethod(contextUtils.active(), declaration, arguments, expectedParameters);
			}
		} else {
			if (declaration.isFunction()) {
				stack.push((LLVMIdentifier<LLVMType>) codeGenerator.call(
				        contextUtils.active(),
				        nameMangler.mangleFunction(declaration),
				        node.getType(),
				        arguments,
				        expectedParameters));
			} else {
				if (declaration.isInitializer()) {
					stack.push((LLVMIdentifier<LLVMType>) arguments.get(0));
				}
				codeGenerator.callVoid(
				        contextUtils.active(),
				        nameMangler.mangleFunction(declaration),
				        arguments,
				        expectedParameters);
			}
		}
	}

	@Override
	public void visit(FunctionDeclaration node) {
		TypeDeclaration returnType = node.getReturnType();
		if (returnType instanceof ClassDeclaration) {
			codeGenerator.mapAbstractGenericToConcreteIfApplicable((ClassDeclaration) returnType);
		}
		if (node.isAbstract()) {
			openNewFunctionScope();
			if ((returnType == null) || (returnType == CoreClasses.voidType())) {
				addFunction(node, CoreClasses.voidType());
				codeGenerator.returnValue(
				        contextUtils.active(),
				        (LLVMIdentifier<LLVMType>) (LLVMIdentifier<?>) llvmIdentifierFactory.voidId(),
				        CoreClasses.voidType());
			} else {
				addFunction(node, returnType);
				codeGenerator.returnValue(
				        contextUtils.active(),
				        (LLVMIdentifier<LLVMType>) (LLVMIdentifier<?>) llvmIdentifierFactory.constantNull((LLVMPointer) codeGenerator.mapToLLVMType(returnType)),
				        returnType);
			}
			closeFunctionContext();
		} else {
			if (node.isFunction()) {
				openNewFunctionScope();
				if (isNative(node)) {
					addNativeFunction(node, returnType);
				} else {
					addFunction(node, returnType);
					visitDoubleDispatched(node.getBody());
				}
				closeFunctionContext();
			} else {
				openNewFunctionScope();
				if (isNative(node) && !node.isInitializer()) {
					addNativeFunction(node, returnType);
				} else {
					addFunction(node, returnType);

					visitDoubleDispatched(node.getBody());
					if (node.isInitializer()) {
						if (node.getDefiningClass().isGenerator()) {
							setGeneratorLabel(node.getDefiningClass(), "startGenerator");
						}
						codeGenerator.returnValue(
						        contextUtils.active(),
						        (LLVMIdentifier<LLVMType>) (LLVMIdentifier<?>) llvmIdentifierFactory.voidId(),
						        CoreClasses.voidType());
					}
				}
				closeFunctionContext();
			}
		}
	}

	@Override
	public void visit(ReturnStatement node) {
		super.visit(node);
		// if we have a yield statement here, we have to set the generator jump destination to this label
		if (node instanceof YieldStatement) {
			setGeneratorLabel((ClassDeclaration) node.getParentNodeByType(ClassDeclaration.class), "yield"
			        + ((YieldStatement) node).getYieldStatementIndex());
		}

		if (node.getParameter() != null) {
			ASTNode parent = node.getParentNodeByType(FunctionDeclaration.class);
			LLVMIdentifier<LLVMType> returnValue = stack.pop();
			TypeDeclaration returnType = ((FunctionDeclaration) parent).getReturnType();
			if (returnType instanceof ClassDeclaration) {
				returnType = codeGenerator.mapAbstractGenericToConcreteIfApplicable((ClassDeclaration) returnType);
			}

			codeGenerator.returnValue(contextUtils.active(), returnValue, returnType);
		} else {
			codeGenerator.returnValue(
			        contextUtils.active(),
			        (LLVMIdentifier<LLVMType>) (LLVMIdentifier<?>) llvmIdentifierFactory.voidId(),
			        CoreClasses.voidType());
		}
		// if we have a yield statement here, we have to add a label to jump to
		if (node instanceof YieldStatement) {
			String yieldLabel = "yield" + ((YieldStatement) node).getYieldStatementIndex();
			contextUtils.active().label(yieldLabel);
		}
	}

	@Override
	public void visit(ConditionalStatement node) {
		visitDoubleDispatched(node.getCondition());

		String ifPre = codeGenerator.createLabelPrefix("if", node);

		String ifTrue = ifPre + ".true";
		String ifFalse = ifPre + ".false";
		String ifEnd = ifPre + ".end";

		LLVMIdentifier<LLVMType> condition = stack.pop();
		codeGenerator.branch(contextUtils.active(), condition, ifTrue, ifFalse);

		contextUtils.active().label(ifTrue);
		visitDoubleDispatched(node.getThenBlock());
		contextUtils.active().branch(ifEnd);

		contextUtils.active().label(ifFalse);
		visitDoubleDispatched(node.getElseBlock());
		contextUtils.active().branch(ifEnd);

		contextUtils.active().label(ifEnd);
	}

	@Override
	public void visit(WhileLoop node) {

		String whlPre = codeGenerator.createLabelPrefix("while", node);
		String whileCond = whlPre + ".condition";
		String whileBlk = whlPre + ".block";
		String whileEnd = whlPre + ".end";

		contextUtils.active().branch(whileCond);
		contextUtils.active().label(whileCond);
		visitDoubleDispatched(node.getCondition());

		LLVMIdentifier<LLVMType> condition = stack.pop();
		codeGenerator.branch(contextUtils.active(), condition, whileBlk, whileEnd);

		contextUtils.active().label(whileBlk);
		visitDoubleDispatched(node.getBody());
		contextUtils.active().branch(whileCond);
		contextUtils.active().label(whileEnd);
	}

	@Override
	public void visit(SkipStatement node) {
		super.visit(node);
		String whlPre = codeGenerator.getLabelPrefix(node.getLoop());
		contextUtils.active().branch(whlPre + ".condition");
	}

	@Override
	public void visit(BreakStatement node) {
		super.visit(node);
		String whlPre = codeGenerator.getLabelPrefix(node.getLoop());
		contextUtils.active().branch(whlPre + ".end");
	}
}
