package com.chocohead.merger.menu;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.DoubleConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javafx.scene.Node;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import net.fabricmc.stitch.merge.JarMerger;

import org.objectweb.asm.Opcodes;

import matcher.Matcher;
import matcher.NameType;
import matcher.Util;
import matcher.gui.Gui;
import matcher.type.ClassEnvironment;
import matcher.type.ClassInstance;
import matcher.type.FieldInstance;
import matcher.type.InputFile;
import matcher.type.MemberInstance;
import matcher.type.MethodInstance;

import com.chocohead.merger.mappings.TinyWriter;
import com.chocohead.merger.pane.ExportJarPane;
import com.chocohead.merger.pane.ExportJarPane.Type;

public class MergedJaringMenu extends Menu {
	public MergedJaringMenu(Gui gui) {
		super("Merge Exporting");

		MenuItem item = new MenuItem("Dump merged jar");
		item.setOnAction(event -> dumpMergedJar(gui));
		getItems().add(item);

		item = new MenuItem("Clear UIDs");
		item.setOnAction(event -> gui.runProgressTask("Clearing UIDs", progress -> clearGlue(gui, progress), () -> {}, Throwable::printStackTrace));
		getItems().add(item);

		getItems().add(new SeparatorMenuItem());

		item = new MenuItem("Filter Argo");
		item.setOnAction(event -> gui.showAlert(AlertType.INFORMATION, "Filtering out Argo types", "TODO", "//Allow picking side to apply on"));
		getItems().add(item);

		item = new MenuItem("Find inner classes");
		item.setOnAction(event -> gui.runProgressTask("Finding likely nested inner classes...", progress -> findInnerClasses(gui, progress), () -> {}, Throwable::printStackTrace));
		getItems().add(item);
	}

	private static void dumpMergedJar(Gui gui) {
		Dialog<ExportJarPane> dialog = new Dialog<>();
		dialog.setResizable(true);
		dialog.setTitle("Export configuration");
		dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

		Node okButton = dialog.getDialogPane().lookupButton(ButtonType.OK);
		ExportJarPane content = new ExportJarPane(dialog.getOwner(), okButton);

		dialog.getDialogPane().setContent(content);
		dialog.setResultConverter(button -> button == ButtonType.OK ? content : null);

		dialog.showAndWait().ifPresent(export -> {
			assert export != null;
			assert export.isValid();

			if (export.shouldFixArgo()) {
				gui.showAlert(AlertType.INFORMATION, "Information", "Information", "Argo detection is still WIP");
			}

			if (export.shouldFixInners()) {
				gui.showAlert(AlertType.INFORMATION, "Information", "Information", "The inner class wizard is still WIP");
			}

			gui.runProgressTask("Exporting merged jar...", progress -> dumpMergedJar(gui, export, progress), () -> {}, Throwable::printStackTrace);
		});
	}

	private static void dumpMergedJar(Gui gui, ExportJarPane export, DoubleConsumer progress) {
		assignGlue(gui, progress);
		exportGlue(gui, export.getMappingsFile(), export.getMappingsType(), export.isServerA(), progress);

		ClassEnvironment env = gui.getEnv();
		Path aIn = pullInput(env.getInputFilesA());
		Path bIn = pullInput(env.getInputFilesB());

		try (JarMerger merger = new JarMerger(export.isClientA() ? env::getClsByNameA : env::getClsByNameB, export.isServerA() ? env::getClsByNameA : env::getClsByNameB, export.isClientA() ? aIn : bIn, export.isServerA() ? aIn : bIn, export.getMergeJar())) {
			System.out.println("Merging...");

			merger.merge();

			System.out.println("Merge completed!");
		} catch (IOException e) {
			throw new UncheckedIOException("Error merging jars", e);
		}
	}

	private static Path pullInput(Collection<InputFile> inputs) {
		if (inputs.size() != 1) throw new UnsupportedOperationException("Unable to merge multiple input files");
		InputFile input = inputs.iterator().next();

		return input.hasPath() ? input.path : Paths.get(input.fileName);
	}

	private static void assignGlue(Gui gui, DoubleConsumer progress) {
		int nextClassID = 1;
		int nextMethodID = 1;
		int nextFieldID = 1;

		List<ClassInstance> classes = new ArrayList<>(gui.getEnv().getClasses());
		classes.sort(Comparator.comparing(ClassInstance::getName));

		List<MethodInstance> methods = new ArrayList<>();
		List<FieldInstance> fields = new ArrayList<>();

		double progressAmount = 1;
		for (ClassInstance cls : classes) {
			assert cls.isInput();

			if (cls.isNameObfuscated()) {
				if (cls.hasMatch() && cls.getUid() >= 0) {
					assert cls.getUid() == cls.getMatch().getUid();
				} else {
					assert cls.getUid() < 0;
					int id;
					cls.setUid(id = nextClassID++);
					assert cls.getUid() == id: "Failed to claim UID for " + cls;
				}
			}

			for (MethodInstance method : cls.getMethods()) {
				if (method.isNameObfuscated() && method.getUid() < 0) {
					methods.add(method);
				}
			}

			if (!methods.isEmpty()) {
				methods.sort(MemberInstance.nameComparator);

				for (MethodInstance method : methods) {
					int id;
					int[] UIDs = method.getAllHierarchyMembers().stream().mapToInt(MethodInstance::getUid).distinct().sorted().toArray();
					if (UIDs.length > 2 || UIDs.length == 2 && UIDs[0] != -1) {
						throw new IllegalStateException("Inconsistent method hierachy naming: " + Arrays.toString(UIDs));
					} else if (UIDs.length == 2) {
						id = UIDs[1];
					} else {
						assert UIDs.length == 1;
						id = UIDs[0] == -1 ? nextMethodID++ : UIDs[0];
					}

					for (MethodInstance m : method.getAllHierarchyMembers()) {
						assert m.getUid() <= 0 || m.getUid() == id: "Changed " + m + " UID from " + m.getUid() + " to " + id + ", hierachy is " + method.getAllHierarchyMembers().stream().map(mtd -> mtd + " => " + mtd.getUid()).collect(Collectors.joining(", ", "[", "]"));
						m.setUid(id);
						assert m.getUid() == id: "Failed to claim UID for " + m;
					}
				}

				methods.clear();
			}

			for (FieldInstance field : cls.getFields()) {
				if (field.isNameObfuscated() && field.getUid() < 0) {
					fields.add(field);
				}
			}

			if (!fields.isEmpty()) {
				fields.sort(MemberInstance.nameComparator);

				for (FieldInstance field : fields) {
					int id;
					field.setUid(id = nextFieldID++);
					assert field.getUid() == id: "Failed to claim UID for " + field;
					assert field.getAllHierarchyMembers().size() == 1;
				}

				fields.clear();
			}

			progress.accept(progressAmount++ / classes.size());
		}

		System.out.printf("Generated glue IDs: %d classes, %d methods, %d fields%n", nextClassID, nextMethodID, nextFieldID);
	}

	private static void exportGlue(Gui gui, Path to, Type type, boolean serverFirst, DoubleConsumer progress) {
		TinyWriter writer;
		try {
			Files.deleteIfExists(to);

			switch (type) {
			case Tiny:
				writer = TinyWriter.normal(to);
				break;

			case CompressedTiny:
				writer = TinyWriter.compressed(to);
				break;

			case TinyV2:
				writer = TinyWriter.v2(to);
				break;

			default:
				throw new IllegalStateException("Unexpected export type: " + type);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Error opening glue export file at " + to, e);
		}

		List<ClassInstance> union = new ArrayList<>();
		List<ClassInstance> serverOnly = new ArrayList<>();
		List<ClassInstance> clientOnly = new ArrayList<>();

		for (ClassInstance cls : serverFirst ? gui.getEnv().getClassesA() : gui.getEnv().getClassesB()) {
			(cls.hasMatch() ? union : serverOnly).add(cls);
		}
		for (ClassInstance cls : serverFirst ? gui.getEnv().getClassesB() : gui.getEnv().getClassesA()) {
			if (!cls.hasMatch()) {
				clientOnly.add(cls);
			} else {
				assert union.contains(cls.getMatch());
			}
		}

		union.sort(Comparator.comparing(ClassInstance::getName));
		serverOnly.sort(Comparator.comparing(ClassInstance::getName));
		clientOnly.sort(Comparator.comparing(ClassInstance::getName));

		double total = union.size() + serverOnly.size() + clientOnly.size();
		int current = 1;

		for (ClassInstance cls : union) {
			assert cls.isInput();

			String className;
			if (cls.isNameObfuscated()) {
				assert cls.getUid() >= 0: "Missed UID for " + cls;
				assert Objects.equals(cls.getName(NameType.UID_PLAIN), cls.getMatch().getName(NameType.UID_PLAIN));
				writer.acceptClass(className = cls.getName(cls.hasMappedName() ? NameType.MAPPED_PLAIN : NameType.UID_PLAIN), cls.getName(NameType.PLAIN), cls.getMatch().getName(NameType.PLAIN));
			} else {
				className = cls.getName();
			}

			for (MethodInstance method : cls.getMethods()) {
				if (method.isNameObfuscated()) {
					assert method.getUid() >= 0: "Missed UID for " + method;
					writer.acceptMethod(className, method.getName(method.hasMappedName() ? NameType.MAPPED_PLAIN : NameType.UID_PLAIN), method.getDesc(),
							method.getName(NameType.PLAIN), method.hasMatch() ? method.getMatch().getName(NameType.PLAIN) : null);
				}
			}
			for (MethodInstance method : cls.getMatch().getMethods()) {
				if (!method.hasMatch() && method.isNameObfuscated()) {
					assert method.getUid() >= 0: "Missed UID for " + method + " (class matched to " + cls + ')';
					writer.acceptMethod(className, method.getName(method.hasMappedName() ? NameType.MAPPED_PLAIN : NameType.UID_PLAIN), method.getDesc(), null, method.getName(NameType.PLAIN));
				}
			}

			for (FieldInstance field : cls.getFields()) {
				if (field.isNameObfuscated()) {
					assert field.getUid() >= 0: "Missed UID for " + field;
					writer.acceptField(className, field.getName(field.hasMappedName() ? NameType.MAPPED_PLAIN : NameType.UID_PLAIN), field.getDesc(),
							field.getName(NameType.PLAIN), field.hasMatch() ? field.getMatch().getName(NameType.PLAIN) : null);
				}
			}
			for (FieldInstance field : cls.getMatch().getFields()) {
				if (!field.hasMatch() && field.isNameObfuscated()) {
					assert field.getUid() >= 0: "Missed UID for " + field + " (class matched to " + cls + ')';
					writer.acceptField(className, field.getName(field.hasMappedName() ? NameType.MAPPED_PLAIN : NameType.UID_PLAIN), field.getDesc(), null, field.getName(NameType.PLAIN));
				}
			}

			progress.accept(current++ / total);
		}

		for (ClassInstance cls : serverOnly) {
			assert cls.isInput();

			String className;
			if (cls.isNameObfuscated()) {
				assert cls.getUid() >= 0;
				writer.acceptClass(className = cls.getName(cls.hasMappedName() ? NameType.MAPPED_PLAIN : NameType.UID_PLAIN), cls.getName(NameType.PLAIN), null);
			} else {
				className = cls.getName();
			}

			for (MethodInstance method : cls.getMethods()) {
				if (method.isNameObfuscated()) {
					assert method.getUid() >= 0;
					writer.acceptMethod(className, method.getName(method.hasMappedName() ? NameType.MAPPED_PLAIN : NameType.UID_PLAIN), method.getDesc(), method.getName(NameType.PLAIN), null);
				}
			}

			for (FieldInstance field : cls.getFields()) {
				if (field.isNameObfuscated()) {
					assert field.getUid() >= 0;
					writer.acceptField(className, field.getName(field.hasMappedName() ? NameType.MAPPED_PLAIN : NameType.UID_PLAIN), field.getDesc(), field.getName(NameType.PLAIN), null);
				}
			}

			progress.accept(current++ / total);
		}

		for (ClassInstance cls : clientOnly) {
			assert cls.isInput();

			String className;
			if (cls.isNameObfuscated()) {
				assert cls.getUid() >= 0;
				writer.acceptClass(className = cls.getName(cls.hasMappedName() ? NameType.MAPPED_PLAIN : NameType.UID_PLAIN), null, cls.getName(NameType.PLAIN));
			} else {
				className = cls.getName();
			}

			for (MethodInstance method : cls.getMethods()) {
				if (method.isNameObfuscated()) {
					assert method.getUid() >= 0;
					writer.acceptMethod(className, method.getName(method.hasMappedName() ? NameType.MAPPED_PLAIN : NameType.UID_PLAIN), method.getDesc(), null, method.getName(NameType.PLAIN));
				}
			}

			for (FieldInstance field : cls.getFields()) {
				if (field.isNameObfuscated()) {
					assert field.getUid() >= 0;
					writer.acceptField(className, field.getName(field.hasMappedName() ? NameType.MAPPED_PLAIN : NameType.UID_PLAIN), field.getDesc(), null, field.getName(NameType.PLAIN));
				}
			}

			progress.accept(current++ / total);
		}

		Util.closeSilently(writer);
	}

	private static void clearGlue(Gui gui, DoubleConsumer progress) {
		List<MethodInstance> methods = new ArrayList<>();
		List<FieldInstance> fields = new ArrayList<>();

		double toDo = gui.getEnv().getClasses().size();
		int progressAmount = 1;

		for (ClassInstance cls : gui.getEnv().getClasses()) {
			assert cls.isInput();

			if (cls.isNameObfuscated() && cls.getUid() >= 0) {
				cls.setUid(-1);
			}

			for (MethodInstance method : cls.getMethods()) {
				if (method.isNameObfuscated() && method.getUid() >= 0) {
					methods.add(method);
				}
			}

			if (!methods.isEmpty()) {
				for (MethodInstance method : methods) {
					for (MethodInstance m : method.getAllHierarchyMembers()) {
						m.setUid(-1);
					}
				}

				methods.clear();
			}

			for (FieldInstance field : cls.getFields()) {
				if (field.isNameObfuscated() && field.getUid() >= 0) {
					fields.add(field);
				}
			}

			if (!fields.isEmpty()) {
				for (FieldInstance field : cls.getFields()) {
					field.setUid(-1);
				}

				fields.clear();
			}

			progress.accept(progressAmount++ / toDo);
		}
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

	private static void findInnerClasses(Gui gui, DoubleConsumer progress) {
		List<ClassInstance> classes = Stream.concat(gui.getEnv().getClassesA().stream(), gui.getEnv().getClassesB().stream()).filter(cls -> cls.getUri() != null && cls.isNameObfuscated()).collect(Collectors.toList());
		List<ClassSystem> systems = new ArrayList<>();

		Matcher.runInParallel(classes, cls -> {
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