package com.chocohead.merger;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Predicate;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import matcher.Util;
import matcher.classifier.ClassifierUtil;
import matcher.type.ClassEnvironment;
import matcher.type.ClassInstance;
import matcher.type.FieldInstance;
import matcher.type.MethodInstance;
import matcher.type.MethodVarInstance;

public class MethodCloseness {
	public static boolean isCloseEnough(MethodInstance a, MethodInstance b) {
		if (!a.isReal() || !b.isReal()) return false;

		InsnList ilA = cloneWithoutFrames(a.getAsmNode().instructions);
		InsnList ilB = cloneWithoutFrames(b.getAsmNode().instructions);
		if (ilA.size() != ilB.size()) return false;

		for (int i = 0; i < ilA.size(); i++) {
			if (!instructionsMatch(ilA.get(i), ilB.get(i), ilA, ilB, a, b, a.getEnv().getGlobal())) {
				//Not necessarily true as we test line numbers which ClassifierUtil ignores
				//assert ClassifierUtil.compareInsns(a, b) < 1 - 1e-6;
				return false;
			}
		}

		assert ilA.size() != a.getAsmNode().instructions.size() || ilB.size() != b.getAsmNode().instructions.size() || ClassifierUtil.compareInsns(a, b) >= 1 - 1e-6;
		return true;
	}

	public static InsnList cloneWithoutFrames(InsnList list) {
		return clone(list, insn -> insn.getType() != AbstractInsnNode.FRAME && insn.getType() != AbstractInsnNode.LABEL);
	}

	private static InsnList clone(InsnList list, Predicate<AbstractInsnNode> filter) {
		Map<LabelNode, LabelNode> clonedLabels = new IdentityHashMap<>();
		Map<Label, Label> trueLabels = new IdentityHashMap<>();

		boolean seenFrame = false;
		for (Iterator<AbstractInsnNode> it = list.iterator(); it.hasNext();) {
			AbstractInsnNode insn = it.next();

			switch (insn.getType()) {
			case AbstractInsnNode.LABEL:
				LabelNode node = (LabelNode) insn;

				clonedLabels.put(node, new LabelNode(trueLabels.computeIfAbsent(node.getLabel(), k -> new Label())));
				break;

			case AbstractInsnNode.FRAME:
				seenFrame = true;
				break;
			}
		}
		if (!seenFrame && clonedLabels.isEmpty()) return list; //Only clone the list if we have to

		InsnList out = new InsnList();
		for (Iterator<AbstractInsnNode> it = list.iterator(); it.hasNext();) {
			AbstractInsnNode insn = it.next();

			if (filter.test(insn)) out.add(insn.clone(clonedLabels));
		}

		return out;
	}

	private static boolean instructionsMatch(AbstractInsnNode insnA, AbstractInsnNode insnB, InsnList listA, InsnList listB, MethodInstance mthA, MethodInstance mthB, ClassEnvironment env) {
		if (insnA.getOpcode() != insnB.getOpcode()) return false;

		switch (insnA.getType()) {
		case AbstractInsnNode.INT_INSN: {
			IntInsnNode a = (IntInsnNode) insnA;
			IntInsnNode b = (IntInsnNode) insnB;

			return a.operand == b.operand;
		}

		case AbstractInsnNode.VAR_INSN: {
			VarInsnNode a = (VarInsnNode) insnA;
			VarInsnNode b = (VarInsnNode) insnB;

			if (mthA != null && mthB != null) {
				MethodVarInstance varA = mthA.getArgOrVar(a.var, listA.indexOf(insnA));
				MethodVarInstance varB = mthB.getArgOrVar(b.var, listB.indexOf(insnB));

				if (varA != null && varB != null) {
					return ClassifierUtil.checkPotentialEquality(varA, varB);
				}
			}

			break;
		}

		case AbstractInsnNode.TYPE_INSN: {
			TypeInsnNode a = (TypeInsnNode) insnA;
			TypeInsnNode b = (TypeInsnNode) insnB;
			ClassInstance clsA = env.getClsByNameA(a.desc);
			ClassInstance clsB = env.getClsByNameB(b.desc);

			return ClassifierUtil.checkPotentialEqualityNullable(clsA, clsB);
		}

		case AbstractInsnNode.FIELD_INSN: {
			FieldInsnNode a = (FieldInsnNode) insnA;
			FieldInsnNode b = (FieldInsnNode) insnB;
			ClassInstance clsA = env.getClsByNameA(a.owner);
			ClassInstance clsB = env.getClsByNameB(b.owner);

			if (clsA == null && clsB == null) return true;
			if (clsA == null || clsB == null) return false;

			FieldInstance fieldA = clsA.resolveField(a.name, a.desc);
			FieldInstance fieldB = clsB.resolveField(b.name, b.desc);

			return ClassifierUtil.checkPotentialEqualityNullable(fieldA, fieldB);
		}

		case AbstractInsnNode.METHOD_INSN: {
			MethodInsnNode a = (MethodInsnNode) insnA;
			MethodInsnNode b = (MethodInsnNode) insnB;

			return methodsMatch(a.owner, a.name, a.desc, Util.isCallToInterface(a), b.owner, b.name, b.desc, Util.isCallToInterface(b), env);
		}

		case AbstractInsnNode.INVOKE_DYNAMIC_INSN: {
			InvokeDynamicInsnNode a = (InvokeDynamicInsnNode) insnA;
			InvokeDynamicInsnNode b = (InvokeDynamicInsnNode) insnB;

			if (!a.bsm.equals(b.bsm)) return false;

			if (Util.isJavaLambdaMetafactory(a.bsm)) {
				Handle implA = (Handle) a.bsmArgs[1];
				Handle implB = (Handle) b.bsmArgs[1];

				if (implA.getTag() != implB.getTag()) return false;

				switch (implA.getTag()) {
				case Opcodes.H_INVOKEVIRTUAL:
				case Opcodes.H_INVOKESTATIC:
				case Opcodes.H_INVOKESPECIAL:
				case Opcodes.H_NEWINVOKESPECIAL:
				case Opcodes.H_INVOKEINTERFACE:
					return methodsMatch(implA.getOwner(), implA.getName(), implA.getDesc(), Util.isCallToInterface(implA), implB.getOwner(), implB.getName(), implB.getDesc(), Util.isCallToInterface(implB), env);
				default:
					throw new IllegalStateException("Unexpected impl tag: " + implA.getTag());
				}
			} else {
				throw new IllegalStateException(String.format("unknown invokedynamic bsm: %s/%s%s (tag=%d iif=%b)%n", a.bsm.getOwner(), a.bsm.getName(), a.bsm.getDesc(), a.bsm.getTag(), a.bsm.isInterface()));
			}
		}

		case AbstractInsnNode.JUMP_INSN: {
			JumpInsnNode a = (JumpInsnNode) insnA;
			JumpInsnNode b = (JumpInsnNode) insnB;

			// check if the 2 jumps have the same direction
			return Integer.signum(listA.indexOf(a.label) - listA.indexOf(a)) == Integer.signum(listB.indexOf(b.label) - listB.indexOf(b));
		}

		case AbstractInsnNode.LABEL: {
			break;
		}

		case AbstractInsnNode.LDC_INSN: {
			LdcInsnNode a = (LdcInsnNode) insnA;
			LdcInsnNode b = (LdcInsnNode) insnB;
			Class<?> typeClsA = a.cst.getClass();

			if (typeClsA != b.cst.getClass()) return false;

			if (typeClsA == Type.class) {
				Type typeA = (Type) a.cst;
				Type typeB = (Type) b.cst;

				if (typeA.getSort() != typeB.getSort()) return false;

				switch (typeA.getSort()) {
				case Type.ARRAY:
				case Type.OBJECT:
					return ClassifierUtil.checkPotentialEqualityNullable(env.getClsByIdA(typeA.getDescriptor()), env.getClsByIdB(typeB.getDescriptor()));
				case Type.METHOD:
					break;
				}
			} else {
				return a.cst.equals(b.cst);
			}

			break;
		}

		case AbstractInsnNode.IINC_INSN: {
			IincInsnNode a = (IincInsnNode) insnA;
			IincInsnNode b = (IincInsnNode) insnB;

			if (a.incr != b.incr) return false;

			if (mthA != null && mthB != null) {
				MethodVarInstance varA = mthA.getArgOrVar(a.var, listA.indexOf(insnA));
				MethodVarInstance varB = mthB.getArgOrVar(b.var, listB.indexOf(insnB));

				if (varA != null && varB != null) {
					return ClassifierUtil.checkPotentialEquality(varA, varB);
				}
			}

			break;
		}

		case AbstractInsnNode.TABLESWITCH_INSN: {
			TableSwitchInsnNode a = (TableSwitchInsnNode) insnA;
			TableSwitchInsnNode b = (TableSwitchInsnNode) insnB;

			return a.min == b.min && a.max == b.max;
		}

		case AbstractInsnNode.LOOKUPSWITCH_INSN: {
			LookupSwitchInsnNode a = (LookupSwitchInsnNode) insnA;
			LookupSwitchInsnNode b = (LookupSwitchInsnNode) insnB;

			return a.keys.equals(b.keys);
		}

		case AbstractInsnNode.MULTIANEWARRAY_INSN: {
			MultiANewArrayInsnNode a = (MultiANewArrayInsnNode) insnA;
			MultiANewArrayInsnNode b = (MultiANewArrayInsnNode) insnB;

			if (a.dims != b.dims) return false;

			ClassInstance clsA = env.getClsByNameA(a.desc);
			ClassInstance clsB = env.getClsByNameB(b.desc);

			return ClassifierUtil.checkPotentialEqualityNullable(clsA, clsB);
		}

		case AbstractInsnNode.FRAME: {
			break;
		}

		case AbstractInsnNode.LINE: {
			LineNumberNode a = (LineNumberNode) insnA;
			LineNumberNode b = (LineNumberNode) insnB;

			return a.line == b.line;
		}
		}

		return true;
	}

	private static boolean methodsMatch(String ownerA, String nameA, String descA, boolean toIfA, String ownerB, String nameB, String descB, boolean toIfB, ClassEnvironment env) {
		ClassInstance clsA = env.getClsByNameA(ownerA);
		ClassInstance clsB = env.getClsByNameB(ownerB);

		if (clsA == null && clsB == null) return true;
		if (clsA == null || clsB == null) return false;

		MethodInstance methodA = clsA.resolveMethod(nameA, descA, toIfA);
		MethodInstance methodB = clsB.resolveMethod(nameB, descB, toIfB);

		if (methodA == null && methodB == null) return true;
		if (methodA == null || methodB == null) return false;

		return ClassifierUtil.checkPotentialEquality(methodA, methodB);
	}
}