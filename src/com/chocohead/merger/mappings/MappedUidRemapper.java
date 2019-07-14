package com.chocohead.merger.mappings;

import matcher.NameType;
import matcher.bcremap.AsmRemapper;
import matcher.type.ClassEnv;
import matcher.type.ClassInstance;

public class MappedUidRemapper extends AsmRemapper {
	protected final AsmRemapper other;

	public MappedUidRemapper(ClassEnv env) {
		super(env, NameType.MAPPED_PLAIN);

		other = new AsmRemapper(env, NameType.UID_PLAIN);
	}

	@Override
	public String map(String typeName) {
		String out = super.map(typeName);

		if (!typeName.equals(out)) {
			if (!ClassInstance.hasOuterName(out)) return out;

			/*String newOuter = out.substring(0, out.lastIndexOf('$'));
			out = ClassInstance.getInnerName(out);
			String outerRename = super.map(newOuter);

			while (!newOuter.equals(outerRename)) {
				assert super.map(typeName).equals(outerRename + '$' + out);

				if (!ClassInstance.hasOuterName(newOuter)) {
					//Potentially unlikely
					System.out.println("Fully renamed class from mapped names: " + typeName + " => " + newOuter + '$' + out);
					return newOuter + '$' + out;
				}

				out = ClassInstance.getInnerName(newOuter) + '$' + out;
				newOuter = newOuter.substring(0, newOuter.lastIndexOf('$'));
				outerRename = super.map(newOuter);
			}

			return other.map(newOuter) + '$' + out;*/

			StringBuilder newOut = new StringBuilder();
			do {
				String inner = ClassInstance.getInnerName(out);
				if (!inner.matches("[0-9]+")) {
					inner = other.map(inner);
				}

				out = out.substring(0, out.lastIndexOf('$'));
				newOut.insert(0, '$').insert(0, inner);
			} while (ClassInstance.hasOuterName(out));

			return newOut.insert(0, '$').insert(0, other.map(out)).substring(0, newOut.length() - 1);
		}

		return other.map(typeName);
	}

	@Override
	public String mapFieldName(String owner, String name, String desc) {
		String out = super.mapFieldName(owner, name, desc);
		return !name.equals(out) ? out : other.mapFieldName(owner, name, desc);
	}

	@Override
	public String mapMethodName(String owner, String name, String desc) {
		String out = super.mapMethodName(owner, name, desc);
		return !name.equals(out) ? out : other.mapMethodName(owner, name, desc);
	}

	@Override
	public String mapMethodName(String owner, String name, String desc, boolean itf) {
		String out = super.mapMethodName(owner, name, desc, itf);
		return !name.equals(out) ? out : other.mapMethodName(owner, name, desc, itf);
	}

	@Override
	public String mapArbitraryInvokeDynamicMethodName(String owner, String name) {
		String out = super.mapArbitraryInvokeDynamicMethodName(owner, name);
		return !name.equals(out) ? out : other.mapArbitraryInvokeDynamicMethodName(owner, name);
	}

	@Override
	public String mapLocalVariableName(String className, String methodName, String methodDesc, String name, String desc, int lvIndex, int startInsn, int endInsn) {
		String out = super.mapLocalVariableName(className, methodName, methodDesc, name, desc, lvIndex, startInsn, endInsn);
		return !name.equals(out) ? out : other.mapLocalVariableName(className, methodName, methodDesc, name, desc, lvIndex, startInsn, endInsn);
	}
}