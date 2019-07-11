package com.chocohead.merger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.DoubleConsumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import matcher.Matcher;
import matcher.Util;
import matcher.classifier.ClassifierUtil;
import matcher.gui.Gui;
import matcher.type.ClassEnvironment;
import matcher.type.ClassInstance;
import matcher.type.FieldInstance;
import matcher.type.MethodInstance;
import matcher.type.MethodVarInstance;

public enum MergeStep {
	AutoMatch("Apply auto-match") {
		@Override
		protected void run(Matcher matcher, ClassEnvironment env, DoubleConsumer progress) {
			//matcher.autoMatchClasses(progress);
			//Auto-merging everything makes the latter processes easier
			matcher.autoMatchAll(progress);
		}
	},
	MatchFix("Apply auto-match fix") {
		@Override
		protected void run(Matcher matcher, ClassEnvironment env, DoubleConsumer progress) {
			List<ClassInstance> classes = env.getClassesA().stream()
					.filter(cls -> cls.getUri() != null && cls.isNameObfuscated() && cls.hasMatch() && !cls.isFullyMatched(false))
					.collect(Collectors.toList());
			Queue<ClassInstance> mismatches = new ConcurrentLinkedQueue<>();

			Matcher.runInParallel(classes, cls -> {
				//Look to see if partially matched classes are mismatched from methods with identical signatures having different bytecode
				ClassInstance match = cls.getMatch();
				assert match != null;

				for (MethodInstance method : cls.getMethods()) {
					if (!method.isReal() || !method.hasMatch()) continue;

					if (!MethodCloseness.isCloseEnough(method, method.getMatch())) {
						System.out.println("Method contents mismatch in " + cls.getName() + '#' + method.getName() + method.getDesc() + ", only matched with " + ClassifierUtil.compareInsns(method, method.getMatch()));
						mismatches.add(cls);
					}
				}
			}, progress::accept);

			//Unmatch everything that we've decided is incorrectly matched
			if (!mismatches.isEmpty()) {
				mismatches.forEach(matcher::unmatch);
			}
		}
	},
	UsageMatch("Match by class usage") {
		@Override
		protected void run(Matcher matcher, ClassEnvironment env, DoubleConsumer progress) {
			List<ClassInstance> classes = env.getClassesA().stream()
					.filter(cls -> cls.getUri() != null && cls.isNameObfuscated() && cls.hasMatch() /*&& cls.isFullyMatched(false)*/)
					.collect(Collectors.toList());
			Map<ClassInstance, ClassInstance> matches = new ConcurrentHashMap<>(classes.size());
			Map<MethodInstance, MethodInstance> methodMatches = new ConcurrentHashMap<>(classes.size());
			Map<FieldInstance, FieldInstance> fieldMatches = new ConcurrentHashMap<>(classes.size());

			Matcher.runInParallel(classes, cls -> {
				for (MethodInstance method : cls.getMethods()) {
					//Make sure we've only matching methods that really exist
					if (!method.isReal()) continue;

					//assert method.hasMatch(): "Unmatched method in fully matched class: " + cls.getName() + '#' + method.getName() + method.getDesc();
					//Fully matched classes can still have unmatched methods, let's just avoid them for now
					if (!method.hasMatch()) continue;

					MethodInstance matched = method.getMatch();
					//The matched method should certainly exist too
					assert matched.isReal();

					if (!MethodCloseness.isCloseEnough(method, matched)) {
						System.out.println("Unexpected method contents mismatch in " + cls.getName() + '#' + method.getName() + method.getDesc() + ", only matched with " + ClassifierUtil.compareInsns(method, matched));
						continue;
					}

					InsnList methodIns = MethodCloseness.cloneWithoutFrames(method.getAsmNode().instructions);
					InsnList matchedIns = MethodCloseness.cloneWithoutFrames(matched.getAsmNode().instructions);
					assert methodIns.size() == matchedIns.size();

					MethodVarInstance[] methodArgs = method.getArgs();
					MethodVarInstance[] matchedArgs = matched.getArgs();
					assert methodArgs.length == matchedArgs.length;

					for (int i = 0; i < methodArgs.length; i++) {
						ClassInstance clsA = methodArgs[i].getType();
						ClassInstance clsB = matchedArgs[i].getType();

						if (clsA.getMatch() != clsB && matches.get(clsA) != clsB) {
							//System.out.println("Found new class match [" + clsA.getName() + " => " + clsB.getName() + "], previously mapped to " + (clsA.hasMatch() ? cls.getMatch().getName() : "nothing"));
							matches.put(clsA, clsB);
						}
					}

					for (int i = 0; i < methodIns.size(); i++) {
						AbstractInsnNode insnA = methodIns.get(i);
						AbstractInsnNode insnB = matchedIns.get(i);
						assert insnA.getType() == insnB.getType();
						assert insnA.getOpcode() == insnB.getOpcode();

						switch (insnA.getType()) {
						case AbstractInsnNode.TYPE_INSN: {
							TypeInsnNode a = (TypeInsnNode) insnA;
							TypeInsnNode b = (TypeInsnNode) insnB;

							ClassInstance clsA = env.getClsByNameA(a.desc);
							ClassInstance clsB = env.getClsByNameB(b.desc);

							assert clsA != null;
							assert clsB != null;
							if (!clsA.isNameObfuscated()) continue;

							if (clsA.getMatch() != clsB && matches.get(clsA) != clsB) {
								//System.out.println("Found new class match [" + clsA.getName() + " => " + clsB.getName() + "], previously mapped to " + (clsA.hasMatch() ? cls.getMatch().getName() : "nothing"));
								matches.put(clsA, clsB);
							}
							break;
						}
						case AbstractInsnNode.FIELD_INSN: {
							FieldInsnNode a = (FieldInsnNode) insnA;
							FieldInsnNode b = (FieldInsnNode) insnB;

							ClassInstance clsA = env.getClsByNameA(a.owner);
							ClassInstance clsB = env.getClsByNameB(b.owner);

							assert clsA != null;
							assert clsB != null;
							if (!clsA.isNameObfuscated()) continue;

							if (clsA.getMatch() != clsB && matches.get(clsA) != clsB) {
								//System.out.println("Found new class match [" + clsA.getName() + " => " + clsB.getName() + "], previously mapped to " + (clsA.hasMatch() ? cls.getMatch().getName() : "nothing"));
								matches.put(clsA, clsB);
							}

							FieldInstance fieldA = clsA.resolveField(a.name, a.desc);
							FieldInstance fieldB = clsB.resolveField(b.name, b.desc);

							assert fieldA != null;
							assert fieldB != null;

							if (fieldA.getMatch() != fieldB && fieldMatches.get(fieldA) != fieldB) {
								//System.out.println("Found new field match");
								fieldMatches.put(fieldA, fieldB);
							}
							break;
						}
						case AbstractInsnNode.METHOD_INSN: {
							MethodInsnNode a = (MethodInsnNode) insnA;
							MethodInsnNode b = (MethodInsnNode) insnB;

							ClassInstance clsA = env.getClsByNameA(a.owner);
							ClassInstance clsB = env.getClsByNameB(b.owner);

							assert clsA != null;
							assert clsB != null;
							if (!clsA.isNameObfuscated()) continue;

							if (clsA.getMatch() != clsB && matches.get(clsA) != clsB) {
								//System.out.println("Found new class match [" + clsA.getName() + " => " + clsB.getName() + "], previously mapped to " + (clsA.hasMatch() ? cls.getMatch().getName() : "nothing"));
								matches.put(clsA, clsB);
							}

							MethodInstance methodA = clsA.resolveMethod(a.name, a.desc, Util.isCallToInterface(a));
							MethodInstance methodB = clsB.resolveMethod(b.name, b.desc, Util.isCallToInterface(b));

							assert methodA != null;
							assert methodB != null;

							if (methodA.getMatch() != methodB && methodMatches.get(methodA) != methodB) {
								if (!MethodCloseness.isCloseEnough(methodA, methodB)) {
									System.out.println("Expected " + methodA + " and " + methodB + " to be equal, only matched with " + ClassifierUtil.compareInsns(methodA, methodB));
								} else {
									//System.out.println("Found new method match");
									methodMatches.put(methodA, methodB);
								}
							}
							break;
						}
						case AbstractInsnNode.LDC_INSN: {
							LdcInsnNode a = (LdcInsnNode) insnA;
							LdcInsnNode b = (LdcInsnNode) insnB;

							Class<?> typeClsA = a.cst.getClass();
							assert typeClsA == b.cst.getClass();

							if (typeClsA == Type.class) {
								Type typeA = (Type) a.cst;
								Type typeB = (Type) b.cst;

								assert typeA.getSort() == typeB.getSort();

								switch (typeA.getSort()) {
								case Type.ARRAY:
								case Type.OBJECT:
									ClassInstance clsA = env.getClsByIdA(typeA.getDescriptor());
									ClassInstance clsB = env.getClsByIdB(typeB.getDescriptor());

									assert clsA != null;
									assert clsB != null;
									if (!clsA.isNameObfuscated()) continue;

									if (clsA.getMatch() != clsB && matches.get(clsA) != clsB) {
										//System.out.println("Found new class match [" + clsA.getName() + " => " + clsB.getName() + "], previously mapped to " + (clsA.hasMatch() ? cls.getMatch().getName() : "nothing"));
										matches.put(clsA, clsB);
									}
								}
							}
							break;
						}

						default: break;
						}
					}
				}

				for (FieldInstance field : cls.getFields()) {
					//Make sure we've only matching methods that really exist
					if (!field.isReal()) continue;

					//Fully matched classes can still have unmatched fields, let's just avoid them for now
					if (!field.hasMatch()) continue;

					FieldInstance matched = field.getMatch();
					//The matched field should certainly exist too
					assert matched.isReal();

					ClassInstance clsA = field.getType();
					ClassInstance clsB = matched.getType();
					if (!clsA.isNameObfuscated()) continue;

					if (clsA.getMatch() != clsB && matches.get(clsA) != clsB) {
						//System.out.println("Found new class match [" + clsA.getName() + " => " + clsB.getName() + "], previously mapped to " + (clsA.hasMatch() ? cls.getMatch().getName() : "nothing"));
						matches.put(clsA, clsB);
					}
				}
			}, progress::accept);

			Matcher.sanitizeMatches(matches);
			Matcher.sanitizeMatches(methodMatches);
			Matcher.sanitizeMatches(fieldMatches);

			for (Map.Entry<ClassInstance, ClassInstance> entry : matches.entrySet()) {
				matcher.match(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<MethodInstance, MethodInstance> entry : methodMatches.entrySet()) {
				matcher.match(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<FieldInstance, FieldInstance> entry : fieldMatches.entrySet()) {
				matcher.match(entry.getKey(), entry.getValue());
			}
		}
	},
	DetachWrongMethods("Apply mismatched method fix") {
		@Override
		protected void run(Matcher matcher, ClassEnvironment env, DoubleConsumer progress) {
			List<ClassInstance> classes = env.getClassesA().stream()
					.filter(cls -> cls.getUri() != null && cls.isNameObfuscated() && cls.hasMatch())
					.collect(Collectors.toList());
			Queue<MethodInstance> mismatches = new ConcurrentLinkedQueue<>();

			Matcher.runInParallel(classes, cls -> {
				for (MethodInstance method : cls.getMethods()) {
					//Make sure we've only matching methods that really exist
					if (!method.isReal() || !method.hasMatch()) continue;

					MethodInstance matched = method.getMatch();
					//The matched method should certainly exist too
					assert matched.isReal();

					if (!MethodCloseness.isCloseEnough(method, matched)) {
						mismatches.add(method);
						continue;
					}

					MethodVarInstance[] methodArgs = method.getArgs();
					MethodVarInstance[] matchedArgs = matched.getArgs();
					assert methodArgs.length == matchedArgs.length;

					InsnList methodIns = MethodCloseness.cloneWithoutFrames(method.getAsmNode().instructions);
					InsnList matchedIns = MethodCloseness.cloneWithoutFrames(matched.getAsmNode().instructions);
					assert methodIns.size() == matchedIns.size();
				}
			}, progress::accept);

			if (!mismatches.isEmpty()) {
				mismatches.forEach(matcher::unmatch);
			}
		}
	},
	LineNumberMatch("Match by line numbers") {
		@Override
		protected void run(Matcher matcher, ClassEnvironment env, DoubleConsumer progress) {
			List<ClassInstance> classes = env.getClassesA().stream()
					.filter(cls -> cls.getUri() != null && cls.isNameObfuscated() && cls.hasMatch() && !cls.isFullyMatched(false) && cls.getMethods().length > 0)
					.collect(Collectors.toList());
			Map<ClassInstance, ClassInstance> typeMatches = new ConcurrentHashMap<>();
			Map<MethodInstance, MethodInstance> matches = new ConcurrentHashMap<>();

			Matcher.runInParallel(classes, cls -> {
				run(cls, typeMatches, matches);
				//Map<Integer, List<MethodInstance>> methodPool = Arrays.stream(matchCls.getMethods()).collect(Collectors.groupingBy(method -> method.getArgs().length));
			}, progress::accept);

			Matcher.sanitizeMatches(typeMatches);
			Matcher.sanitizeMatches(matches);

			if (!typeMatches.isEmpty()) {
				typeMatches.forEach(matcher::match);
			}
			if (!matches.isEmpty()) {
				matches.forEach(matcher::match);
			}
		}

		//Split into a separate method to fix a generic resolution error that means systems's type both does and doesn't need specifying
		private void run(ClassInstance cls, Map<ClassInstance, ClassInstance> typeMatches, Map<MethodInstance, MethodInstance> matches) {
			ClassInstance matchCls = cls.getMatch();
			assert matchCls != null;

			class MethodMatchSystem {
				final List<MethodInstance> methods = new ArrayList<>();
				final List<MethodInstance> matchMethods = new ArrayList<>();
			}
			List<MethodMatchSystem> systems = new ArrayList<>();

			MethodInstance[] methods = cls.getMethods();
			MethodInstance[] matchMethods = matchCls.getMethods();

			MethodMatchSystem current = null;
			for (int i = 0, j = -1, end = methods.length - 1; i < methods.length; i++) {
				MethodInstance method = methods[i];

				if (method.hasMatch()) {
					if (current != null) {
						while (matchMethods[++j] != method.getMatch()) {
							assert !matchMethods[j].hasMatch(): "Found matched method before another matching pair: " + matchMethods[j] + " => " + matchMethods[j].getMatch() + " whilst looking for " + method + " => " + method.getMatch();
							current.matchMethods.add(matchMethods[j]);
						}

						systems.add(current);
						current = null;
					} else {
						//Push j along until it meets i's match
						while (matchMethods[++j] != method.getMatch());
					}
				} else {
					if (current == null) current = new MethodMatchSystem();

					current.methods.add(method);
				}

				if (i >= end) {
					if (current != null) {
						//Add any remaining unmatched methods
						while (++j < matchMethods.length) {
							assert !matchMethods[j].hasMatch(): matchMethods[j] + " matches back to " + matchMethods[j].getMatch();
							current.matchMethods.add(matchMethods[j]);
						}
					}
					break;
				}
			}

			for (MethodMatchSystem system : systems) {
				int end = 0;

				on: for (MethodInstance method : system.methods) {
					MethodVarInstance[] methodArgs = method.getArgs();
					InsnList methodIns = MethodCloseness.cloneWithoutFrames(method.getAsmNode().instructions);

					off: for (int i = end; i < system.matchMethods.size(); i++) {
						MethodInstance matchMethod = system.matchMethods.get(i);
						if (matchMethod.getArgs().length != methodArgs.length) continue;

						ClassInstance returnType = matchMethod.getRetType();
						if (returnType.hasMatch() && returnType.getMatch() != method.getRetType()) continue;
						if (method.getRetType().hasMatch() && method.getRetType().getMatch() != returnType) continue;

						InsnList matchedIns = MethodCloseness.cloneWithoutFrames(matchMethod.getAsmNode().instructions);
						if (methodIns.size() != matchedIns.size()) continue;

						int seenLines = 0;
						for (int insn = 0; insn < methodIns.size(); insn++) {
							AbstractInsnNode insnA = methodIns.get(insn);
							AbstractInsnNode insnB = matchedIns.get(insn);
							assert insnA.getType() == insnB.getType(): "Mismatch between " + method + " and " + matchMethod + ' ' + insn + " in: " + insnA + " vs " + insnB;
							assert insnA.getOpcode() == insnB.getOpcode(): "Mismatch between " + method + " and " + matchMethod + ' ' + insn + " in: " + insnA + " vs " + insnB;

							if (insnA.getType() == AbstractInsnNode.LINE) {
								seenLines++;
								//Line numbers will always match up for matching methods
								if (((LineNumberNode) insnA).line != ((LineNumberNode) insnB).line) continue off;
							}
						}
						if (seenLines <= 0) System.out.println("Matching " + method + " to " + matchMethod + " without line information");

						if (method.getParents().size() == 1) {
							assert matchMethod.getParents().size() == 1;
							MethodInstance parent = method.getParents().iterator().next();
							MethodInstance matchParent = matchMethod.getParents().iterator().next();

							if (parent.getCls().hasMatch()) {
								assert parent.getCls().getMatch() == matchParent.getCls(): "Mismatched " + parent.getCls() + " to " + matchParent.getCls() + " (expected " + parent.getCls().getMatch() + ')';
							} else {
								typeMatches.put(parent.getCls(), matchParent.getCls());
							}

							matches.put(parent, matchParent);
						}

						matches.put(method, matchMethod);
						end = i + 1;
						continue on;
					}

					System.out.println("Can't find a match for " + method);
				}
			}
		}
	},
	HeirachyMethodMatch("Match by method ownership") {
		@Override
		protected void run(Matcher matcher, ClassEnvironment env, DoubleConsumer progress) {
			List<ClassInstance> classes = env.getClassesA().stream()
					.filter(cls -> cls.getUri() != null && cls.isNameObfuscated() && cls.hasMatch() && cls.getMethods().length > 0)
					.collect(Collectors.toList());
			Map<ClassInstance, ClassInstance> matches = new ConcurrentHashMap<>();

			Matcher.runInParallel(classes, cls -> {
				for (MethodInstance method : cls.getMethods()) {
					if (!method.hasMatch()) continue;

					//assert method.getParents().size() == method.getMatch().getParents().size(): "Parent size difference for " + method + " and " + method.getMatch();
					if (method.getParents().size() != method.getMatch().getParents().size()) {//Apparently methods can have their inheritance stripped
						System.out.println("Parent size difference for " + method + " and " + method.getMatch());
						continue;
					}

					Predicate<MethodInstance> hasMatch = MethodInstance::hasMatch;
					List<MethodInstance> parents = method.getParents().stream().filter(hasMatch.negate()).collect(Collectors.toList());
					List<MethodInstance> matchPatents = method.getMatch().getParents().stream().filter(hasMatch.negate()).collect(Collectors.toList());

					if (parents.size() == 1 && matchPatents.size() == 1) {
						MethodInstance parent = parents.get(0);
						MethodInstance matchParent = matchPatents.get(0);

						if (!parent.getCls().hasMatch()) {
							System.out.println("Matched " + parent.getCls() + " to " + matchParent.getCls() + " from " + method);
							matches.put(parent.getCls(), matchParent.getCls());
						} else {
							assert parent.getCls().getMatch() == matchParent.getCls();
						}
					}
				}
			}, progress::accept);

			Matcher.sanitizeMatches(matches);

			if (!matches.isEmpty()) {
				matches.forEach(matcher::match);
			}
		}
	};

	public final String name;

	private MergeStep(String name) {
		this.name = name;
	}

	public void run(Gui gui, DoubleConsumer progress) {
		run(gui.getMatcher(), gui.getEnv(), progress);
	}

	public void run(Matcher matcher, DoubleConsumer progress) {
		run(matcher, matcher.getEnv(), progress);
	}

	protected abstract void run(Matcher matcher, ClassEnvironment env, DoubleConsumer progress);
}