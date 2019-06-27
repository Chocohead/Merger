package com.chocohead.merger;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

public class RememberingClassWriter extends ClassVisitor {
	public String className;

	public RememberingClassWriter(int flags) {
		super(Opcodes.ASM7, new ClassWriter(flags));
	}

	@Override
	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
		super.visit(version, access, className = name, signature, superName, interfaces);
	}
}