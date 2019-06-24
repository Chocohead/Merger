package com.chocohead.merger.menu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.DoubleConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;

import org.objectweb.asm.Opcodes;

import matcher.Util;
import matcher.gui.Gui;
import matcher.type.ClassInstance;
import matcher.type.FieldInstance;
import matcher.type.MethodInstance;

import com.chocohead.merger.UnsharedMatcher;

public class MergedJaringMenu extends Menu {
	public MergedJaringMenu(Gui gui) {
		super("Merge Exporting");

		MenuItem item = new MenuItem("Find inner classes");
		item.setOnAction(event -> gui.runProgressTask("Finding likely nested inner classes...", progress -> findInnerClasses(gui, progress), () -> {}, Throwable::printStackTrace));
		getItems().add(item);
	}

	private static class ClassSystem {
		public final Set<SystemComponent> classes = Util.newIdentityHashSet();

		public static ClassSystem create(SystemComponent first) {
			ClassSystem out = new ClassSystem();
			out.classes.add(first);
			return out;
		}

		public Set<ClassInstance> involvedClasses() {
			return classes.stream().map(SystemComponent::involvedClasses).flatMap(Set::stream).collect(Collectors.toCollection(Util::newIdentityHashSet));
		}

		public Set<ClassInstance> sniffAdditionalNests() {
			return classes.stream().map(component -> {
				Set<ClassInstance> extra = Util.newIdentityHashSet();
				Set<ClassInstance> known = component.accessedBy();

				for (ClassInstance cls : known) {
					List<ClassInstance> parents = new ArrayList<>();

					ClassInstance parent = cls;
					while ((parent = parent.getSuperClass()) != null) {
						if (!known.contains(parent)) {
							parents.add(parent);
						} else {
							extra.addAll(parents);
							break;
						}
					}
				}

				return extra;
			}).flatMap(Set::stream).collect(Collectors.toCollection(Util::newIdentityHashSet));
		}

		public boolean isSimple() {
			return classes.stream().allMatch(SystemComponent::isInner);
		}
	}

	private static class SystemComponent {
		public final ClassInstance root;
		public final Set<FieldInstance> locals = Util.newIdentityHashSet();
		public final Set<MethodInstance> callsIn = Util.newIdentityHashSet();

		public SystemComponent(ClassInstance root) {
			this.root = root;
		}

		public boolean isInner() {
			return !locals.isEmpty();
		}

		public boolean isAnonymous() {
			return isInner() && callsIn.isEmpty();
		}

		public Set<ClassInstance> localIn() {
			return locals.stream().map(FieldInstance::getType).collect(Collectors.toCollection(Util::newIdentityHashSet));
		}

		public Set<ClassInstance> accessedBy() {
			return callsIn.stream().map(MethodInstance::getCls).collect(Collectors.toCollection(Util::newIdentityHashSet));
		}

		public Set<ClassInstance> involvedClasses() {
			Set<ClassInstance> out = Util.newIdentityHashSet();
			out.add(root);
			out.addAll(localIn());
			out.addAll(accessedBy());
			return out;
		}
	}

	private void findInnerClasses(Gui gui, DoubleConsumer progress) {
		List<ClassInstance> classes = Stream.concat(gui.getEnv().getClassesA().stream(), gui.getEnv().getClassesB().stream()).filter(cls -> cls.getUri() != null && cls.isNameObfuscated()).collect(Collectors.toList());
		List<ClassSystem> systems = new ArrayList<>();

		UnsharedMatcher.runParallel(classes, cls -> {
			if ((cls.getAccess() & Opcodes.ACC_SYNTHETIC) != 0) return; //Synthetic enum switch class

			boolean isNotable = false;
			SystemComponent component = new SystemComponent(cls);

			if (!cls.isEnum()) {//Don't go looking for synthetic fields in an enum as they're always static but will still have one ($VALUES)
				for (FieldInstance field : cls.getFields()) {
					if (field.isReal() && (field.getAccess() & Opcodes.ACC_SYNTHETIC) != 0) {
						assert Arrays.stream(cls.getMethods()).filter(method -> "<init>".equals(method.getName())).count() == 1: "Multiple constructors found in " + cls.getName();

						isNotable = true;
						component.locals.add(field);
					}
				}
			}

			for (MethodInstance method : cls.getMethods()) {
				if (method.isReal() && (method.getAccess() & Opcodes.ACC_SYNTHETIC) != 0) {
					Set<MethodInstance> refsIn = method.getRefsIn();

					if (!refsIn.isEmpty()) {//Will appear unused when method is a bridge override of something else
						System.out.println("Found likely inner class from " + cls.getName() + '#' + method.getName() + method.getDesc() + " (" + (gui.getEnv().getClassesA().contains(cls) ? "A" : "B") + " side)");

						isNotable = true; //TODO: Grab the sole constructor and flag the difference between captured locals and implicit parent type(s)
						component.callsIn.addAll(refsIn);
					}
				}
			}

			if (isNotable) {
				synchronized (systems) {
					List<ClassSystem> matchedSystems = systems.stream().filter(system -> system.involvedClasses().stream().anyMatch(clazz -> component.involvedClasses().contains(clazz))).collect(Collectors.toList());
					switch (matchedSystems.size()) {
					case 0:
						systems.add(ClassSystem.create(component));
						break;

					case 1:
						matchedSystems.get(0).classes.add(component);
						break;

					default:
						ClassSystem merge = matchedSystems.remove(0);
						merge.classes.add(component);

						systems.removeAll(matchedSystems);
						for (ClassSystem system : matchedSystems) {
							merge.classes.addAll(system.classes);
						}

						break;
					}
				}
			}
		}, progress::accept);

		int i = 0;
		for (ClassSystem system : systems) {
			System.out.printf("%n%sSystem %d [%d classes]:%n", system.isSimple() ? "Simple " : "", i++, system.classes.size());

			for (SystemComponent component : system.classes) {
				System.out.printf("\t%s%s (%s):%n\t\tCaptured classes: %s%n\t\tAccessed by: %s%n", component.isInner() ? component.isAnonymous() ? "Anonymous " : "Local " : component.callsIn.isEmpty() ? "Nested(?) " : "",
						component.root, gui.getEnv().getClassesA().contains(component.root) ? "A side" : "B side",
								component.localIn().stream().map(ClassInstance::getName).sorted().collect(Collectors.joining(", ", "[", "]")),
								component.accessedBy().stream().map(ClassInstance::getName).sorted().collect(Collectors.joining(", ", "[", "]")));
			}

			Set<ClassInstance> extra = system.sniffAdditionalNests();
			if (!extra.isEmpty()) {
				System.out.printf("\tProbably with: %s%n", extra.stream().map(ClassInstance::getName).sorted().collect(Collectors.joining(", ", "[", "]")));
			}
		}
	}
}