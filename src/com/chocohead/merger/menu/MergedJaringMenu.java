package com.chocohead.merger.menu;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.DoubleConsumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import net.fabricmc.stitch.merge.JarMerger;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InnerClassNode;

import matcher.Matcher;
import matcher.NameType;
import matcher.Util;
import matcher.bcremap.AsmRemapper;
import matcher.config.Config;
import matcher.gui.Gui;
import matcher.type.ClassEnvironment;
import matcher.type.ClassInstance;
import matcher.type.FieldInstance;
import matcher.type.InputFile;
import matcher.type.MemberInstance;
import matcher.type.MethodInstance;
import matcher.type.MethodVarInstance;

import com.chocohead.merger.MergeStep;
import com.chocohead.merger.QueuingIterator;
import com.chocohead.merger.TripleClassEnvironment;
import com.chocohead.merger.mappings.MappedUidRemapper;
import com.chocohead.merger.mappings.TinyWriter;
import com.chocohead.merger.pane.ArgoConfirmPane;
import com.chocohead.merger.pane.ArgoPane;
import com.chocohead.merger.pane.ExportJarPane;
import com.chocohead.merger.pane.ExportJarPane.Side;
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
		item.setOnAction(event -> mergeArgo(gui));
		getItems().add(item);

		item = new MenuItem("Find inner/nested classes");
		item.setOnAction(event -> gui.runProgressTask("Finding likely nested inner classes...", progress -> findInnerClasses(gui, progress), () -> {}, Throwable::printStackTrace));
		getItems().add(item);

		item = new MenuItem("Fix simple inner classes");
		item.setOnAction(event -> gui.runProgressTask("Fixing simple inner classes...", progress -> fixSimpleInners(gui, Side.BOTH, progress), () -> {}, Throwable::printStackTrace));
		getItems().add(item);

		item = new MenuItem("Run nested class wizard");
		item.setOnAction(event -> gui.showAlert(AlertType.INFORMATION, "Information", "Information", "The nested class wizard is still WIP"));
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

			if (export.shouldFixAnyInners()) {
				if (export.shouldFixSimpleInners()) {
					gui.runProgressTask("Fixing simple inner classes...", progress -> fixSimpleInners(gui, export.sidesToApply(), progress), () -> {}, Throwable::printStackTrace);
				}

				if (export.shouldFixAllInners()) {
					gui.showAlert(AlertType.INFORMATION, "Information", "Information", "The nested class wizard is still WIP");
				}
			}

			gui.runProgressTask("Exporting merged jar...", progress -> dumpMergedJar(gui, export, progress),
					() -> gui.showAlert(AlertType.INFORMATION, "Exporting merged jar...", "Export complete", "Merged jar has been exported to " + export.getMergeJar()),
					t -> {
						t.printStackTrace();
						gui.showAlert(AlertType.ERROR, "Exporting merged jar...", "Export failed", "Jar merging failed due to " + t.getLocalizedMessage() + "\nPlease report this!");
					});
		});
	}

	private static void dumpMergedJar(Gui gui, ExportJarPane export, DoubleConsumer progress) {
		Predicate<ClassInstance> assignSkipper;
		if (!export.excludedA().isEmpty() || !export.excludedB().isEmpty()) {
			Predicate<ClassInstance> aSkipper = exluderFor(export.excludedA());
			Predicate<ClassInstance> bSkipper = exluderFor(export.excludedB());
			assignSkipper = cls -> (gui.getEnv().getClassesA().contains(cls) == export.isServerA() ? aSkipper : bSkipper).test(cls);
		} else {
			assignSkipper = cls -> false;
		}
		assignGlue(gui, assignSkipper, progress);
		exportGlue(gui, export.getMappingsFile(), export.getMappingsType(), export.isServerA(), exluderFor(export.excludedA()), exluderFor(export.excludedB()), progress);

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

	private static Predicate<ClassInstance> exluderFor(String pattern) {
		if (pattern.isEmpty()) return cls -> false;

		Pattern regex = Pattern.compile(pattern);
		return cls -> regex.matcher(cls.getName()).matches();
	}

	private static Path pullInput(Collection<InputFile> inputs) {
		if (inputs.size() != 1) throw new UnsupportedOperationException("Unable to merge multiple input files");
		InputFile input = inputs.iterator().next();

		return input.hasPath() ? input.path : Paths.get(input.fileName);
	}

	private static void assignGlue(Gui gui, Predicate<ClassInstance> skipper, DoubleConsumer progress) {
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
			if (skipper.test(cls)) continue;

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
				if (method.isNameObfuscated() && method.getUid() < 0 && method.getParents().stream().map(MethodInstance::getCls).noneMatch(skipper)) {
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

	private static void exportGlue(Gui gui, Path to, Type type, boolean serverFirst, Predicate<ClassInstance> aSkipper, Predicate<ClassInstance> bSkipper, DoubleConsumer progress) {
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
			if ((serverFirst ? aSkipper : bSkipper).test(cls)) continue;
			(cls.hasMatch() ? union : serverOnly).add(cls);
		}
		for (ClassInstance cls : serverFirst ? gui.getEnv().getClassesB() : gui.getEnv().getClassesA()) {
			if ((serverFirst ? bSkipper : aSkipper).test(cls)) continue;
			if (!cls.hasMatch()) {
				clientOnly.add(cls);
			} else {
				assert union.contains(cls.getMatch());
			}
		}

		union.sort(Comparator.comparing(ClassInstance::getName));
		serverOnly.sort(Comparator.comparing(ClassInstance::getName));
		clientOnly.sort(Comparator.comparing(ClassInstance::getName));

		AsmRemapper serverNamer = new MappedUidRemapper(serverFirst ? gui.getEnv().getEnvA() : gui.getEnv().getEnvB());
		AsmRemapper clientNamer = new MappedUidRemapper(serverFirst ? gui.getEnv().getEnvB() : gui.getEnv().getEnvA());

		double total = union.size() + serverOnly.size() + clientOnly.size();
		int current = 1;

		for (ClassInstance cls : union) {
			assert cls.isInput();

			String className;
			if (cls.isNameObfuscated()) {
				assert cls.getUid() >= 0: "Missed UID for " + cls;
				assert Objects.equals(cls.getName(NameType.UID_PLAIN), cls.getMatch().getName(NameType.UID_PLAIN));
				assert cls.hasMappedName() == cls.getMatch().hasMappedName();
				writer.acceptClass(className = serverNamer.map(cls.getName()), cls.getName(NameType.PLAIN), cls.getMatch().getName(NameType.PLAIN));
			} else {
				className = cls.getName();
			}

			for (MethodInstance method : cls.getMethods()) {
				if (method.isNameObfuscated()) {
					assert method.getUid() >= 0 || method.getParents().stream().map(MethodInstance::getCls).anyMatch(serverFirst ? aSkipper : bSkipper): "Missed UID for " + method;
					writer.acceptMethod(className, serverNamer.mapMethodName(method.getOwner().getName(), method.getName(), method.getDesc()), serverNamer.mapMethodDesc(method.getDesc()),
							method.getName(NameType.PLAIN), method.hasMatch() ? method.getMatch().getName(NameType.PLAIN) : null);
				}
			}
			for (MethodInstance method : cls.getMatch().getMethods()) {
				if (!method.hasMatch() && method.isNameObfuscated()) {
					assert method.getUid() >= 0 || method.getParents().stream().map(MethodInstance::getCls).anyMatch(serverFirst ? bSkipper : aSkipper): "Missed UID for " + method + " (class matched to " + cls + ')';
					writer.acceptMethod(className, clientNamer.mapMethodName(method.getOwner().getName(), method.getName(), method.getDesc()), clientNamer.mapMethodDesc(method.getDesc()), null, method.getName(NameType.PLAIN));
				}
			}

			for (FieldInstance field : cls.getFields()) {
				if (field.isNameObfuscated()) {
					assert field.getUid() >= 0: "Missed UID for " + field;
					writer.acceptField(className, serverNamer.mapFieldName(field.getOwner().getName(), field.getName(), field.getDesc()), serverNamer.mapDesc(field.getDesc()),
							field.getName(NameType.PLAIN), field.hasMatch() ? field.getMatch().getName(NameType.PLAIN) : null);
				}
			}
			for (FieldInstance field : cls.getMatch().getFields()) {
				if (!field.hasMatch() && field.isNameObfuscated()) {
					assert field.getUid() >= 0: "Missed UID for " + field + " (class matched to " + cls + ')';
					writer.acceptField(className, clientNamer.mapFieldName(field.getOwner().getName(), field.getName(), field.getDesc()), clientNamer.mapDesc(field.getDesc()), null, field.getName(NameType.PLAIN));
				}
			}

			progress.accept(current++ / total);
		}

		for (ClassInstance cls : serverOnly) {
			assert cls.isInput();

			String className;
			if (cls.isNameObfuscated()) {
				assert cls.getUid() >= 0;
				writer.acceptClass(className = serverNamer.map(cls.getName()), cls.getName(NameType.PLAIN), null);
			} else {
				className = cls.getName();
			}

			for (MethodInstance method : cls.getMethods()) {
				if (method.isNameObfuscated()) {
					assert method.getUid() >= 0 || method.getParents().stream().map(MethodInstance::getCls).anyMatch(serverFirst ? aSkipper : bSkipper);
					writer.acceptMethod(className, serverNamer.mapMethodName(method.getOwner().getName(), method.getName(), method.getDesc()), serverNamer.mapMethodDesc(method.getDesc()), method.getName(NameType.PLAIN), null);
				}
			}

			for (FieldInstance field : cls.getFields()) {
				if (field.isNameObfuscated()) {
					assert field.getUid() >= 0;
					writer.acceptField(className, serverNamer.mapFieldName(field.getOwner().getName(), field.getName(), field.getDesc()), serverNamer.mapDesc(field.getDesc()), field.getName(NameType.PLAIN), null);
				}
			}

			progress.accept(current++ / total);
		}

		for (ClassInstance cls : clientOnly) {
			assert cls.isInput();

			String className;
			if (cls.isNameObfuscated()) {
				assert cls.getUid() >= 0;
				writer.acceptClass(className = clientNamer.map(cls.getName()), null, cls.getName(NameType.PLAIN));
			} else {
				className = cls.getName();
			}

			for (MethodInstance method : cls.getMethods()) {
				if (method.isNameObfuscated()) {
					assert method.getUid() >= 0 || method.getParents().stream().map(MethodInstance::getCls).anyMatch(serverFirst ? bSkipper : aSkipper);
					writer.acceptMethod(className, clientNamer.mapMethodName(method.getOwner().getName(), method.getName(), method.getDesc()), clientNamer.mapMethodDesc(method.getDesc()), null, method.getName(NameType.PLAIN));
				}
			}

			for (FieldInstance field : cls.getFields()) {
				if (field.isNameObfuscated()) {
					assert field.getUid() >= 0;
					writer.acceptField(className, clientNamer.mapFieldName(field.getOwner().getName(), field.getName(), field.getDesc()), clientNamer.mapDesc(field.getDesc()), null, field.getName(NameType.PLAIN));
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

	private static void mergeArgo(Gui gui) {
		Dialog<ArgoPane> dialog = new Dialog<>();
		dialog.setResizable(true);
		dialog.setTitle("Library matching configuration");
		dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

		Node okButton = dialog.getDialogPane().lookupButton(ButtonType.OK);
		ArgoPane content = new ArgoPane(dialog.getOwner(), okButton);

		dialog.getDialogPane().setContent(content);
		dialog.setResultConverter(button -> button == ButtonType.OK ? content : null);

		dialog.showAndWait().ifPresent(export -> {
			assert export != null;
			assert export.isValid();

			TripleClassEnvironment env = new TripleClassEnvironment(gui.getEnv(), export.getTargetSide() == Side.A);
			Matcher thirdWay = new Matcher(env);

			gui.runProgressTask("Matching in library", progress -> {
				env.init(Collections.singletonList(export.getArgoJar()), Collections.emptyList(), Config.getProjectConfig(), progress);

				QueuingIterator<MergeStep> task = new QueuingIterator<>(MergeStep.values(), MergeStep.UsageMatch);
				Set<ClassInstance> classesToDo = env.getClassesA().stream().filter(cls -> cls.getUri() != null && cls.isNameObfuscated()).collect(Collectors.toCollection(Util::newIdentityHashSet));

				long matched = 0;
				do {
					Set<ClassInstance> previousUnmatchedClasses = classesToDo.stream().filter(cls -> !cls.hasMatch()).collect(Collectors.toCollection(Util::newIdentityHashSet));
					long previousUnmatchedMethods = previousUnmatchedClasses.stream().flatMap(cls -> Arrays.stream(cls.getMethods())).filter(method -> !method.hasMatch()).count();
					long previousUnmatchedFields = previousUnmatchedClasses.stream().flatMap(cls -> Arrays.stream(cls.getFields())).filter(method -> !method.hasMatch()).count();

					assert task.hasNext();
					task.next().run(thirdWay, progress::accept);

					Set<ClassInstance> unmatchedClasses = classesToDo.stream().filter(cls -> !cls.hasMatch()).collect(Collectors.toCollection(Util::newIdentityHashSet));
					long unmatchedMethods = unmatchedClasses.stream().flatMap(cls -> Arrays.stream(cls.getMethods())).filter(method -> !method.hasMatch()).count();
					long unmatchedFields = unmatchedClasses.stream().flatMap(cls -> Arrays.stream(cls.getFields())).filter(method -> !method.hasMatch()).count();

					matched = Math.abs(previousUnmatchedClasses.size() - unmatchedClasses.size()) + Math.abs(previousUnmatchedMethods - unmatchedMethods) + Math.abs(previousUnmatchedFields - unmatchedFields);
					System.out.printf("Matching left %d/%d classes (%+d), %d/%d methods (%+d) and %d/%d fields (%+d) unmatched%n",
							unmatchedClasses.size(), classesToDo.size(), unmatchedClasses.size() - previousUnmatchedClasses.size(),
							unmatchedMethods, classesToDo.stream().flatMap(cls -> Arrays.stream(cls.getMethods())).count(), unmatchedMethods - previousUnmatchedMethods,
							unmatchedFields, classesToDo.stream().flatMap(cls -> Arrays.stream(cls.getFields())).count(), unmatchedFields - previousUnmatchedFields);
				} while (matched > 0 || task.keepGoing());

				Map<Boolean, List<ClassInstance>> pool = classesToDo.stream().collect(Collectors.groupingBy(ClassInstance::hasMatch));

				List<ClassInstance> matches = pool.get(Boolean.TRUE);
				assert !matches.contains(null);
				matches.sort(Comparator.comparing(cls -> cls.getMatch().getName()));

				System.out.println("Matched: " + matches.size() + " out of " + env.getClassesA().size());
				for (ClassInstance match : matches) {
					System.out.println('\t' + match.getMatch().getName() + " => " + match.getName());
				}

				List<ClassInstance> misses = pool.get(Boolean.FALSE);
				assert !misses.contains(null);

				System.out.println("\nMissed: " + misses.size());
				for (ClassInstance miss : misses) {
					System.out.println('\t' + miss.getName());
				}
			}, () -> {
				Alert alert = new ArgoConfirmPane(env);

				if (alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
					gui.runProgressTask("Renaming classes", progress -> {
						int done = 0;
						double toDo = env.getClassesA().size();

						for (ClassInstance cls : env.getClassesA()) {
							progress.accept(done++ / toDo);
							if (cls.getUri() == null || !cls.hasMatch()) continue;

							ClassInstance match = cls.getMatch();

							String name = cls.getName();
							int split;
							if ((split = name.indexOf('$')) > 0) {
								String outerName = name.substring(0, split);
								name = name.substring(split + 1);

								ClassInstance outer = env.getClsByNameA(outerName);
								assert outer != null && outer.hasMatch();

								enforceHierarchy(outer.getMatch(), match);
							}
							match.setMappedName(name);

							for (MethodInstance method : cls.getMethods()) {
								if (!method.isReal() || !method.hasMatch()) continue;

								method.getMatch().setMappedName(method.getName());
							}

							for (FieldInstance field : cls.getFields()) {
								if (!field.isReal() || !field.hasMatch()) continue;

								field.getMatch().setMappedName(field.getName());
							}
						}

						//Could unmatch things as we go, but this avoids any accidents later if the nature of Matcher#unmatch changes
						for (ClassInstance cls : env.getClassesA()) {
							if (cls.getUri() != null && cls.hasMatch()) {
								thirdWay.unmatch(cls); //Clean up once things are mapped otherwise things will trip up later
								assert !cls.hasMatch();
							}
						}

						progress.accept(1);
					}, () -> {}, Throwable::printStackTrace);
				}
			}, Throwable::printStackTrace);
		});
	}

	private static void enforceHierarchy(ClassInstance outer, ClassInstance inner) {
		if (outer.getChildClasses().add(inner)) {
			//Outer didn't know we were one of its children, time to update the node
			String outerName = outer.getName();
			String name = ClassInstance.getInnerName(inner.getName());

			assert !name.contains("/");
			assert outer.getMergedAsmNode() != null: "Asmless outer class: " + outer + " for " + inner;
			outer.getMergedAsmNode().innerClasses.add(new InnerClassNode(ClassInstance.getNestedName(outerName, name), outerName, name, inner.getAccess()));
		}

		if (inner.getOuterClass() != outer) {
			inner.getMergedAsmNode().outerClass = outer.getName();
			try {
				Field f = ClassInstance.class.getDeclaredField("outerClass");
				f.setAccessible(true);
				f.set(inner, outer);
			} catch (ReflectiveOperationException e) {
				throw new RuntimeException("Error setting outerClass field", e);
			}
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

		public boolean isVacuous() {
			return classes.isEmpty() || classes.size() == 1 && classes.iterator().next().involvedClasses().size() < 2;
		}

		public boolean isSimple() {
			return classes.stream().allMatch(SystemComponent::isInner);
		}

		public boolean canBeMadeSimple() {
			return classes.stream().anyMatch(SystemComponent::isInner);
		}

		public ClassSystem makeSimple() {
			if (!canBeMadeSimple()) throw new UnsupportedOperationException();
			if (isSimple()) return this;

			ClassSystem out = new ClassSystem();
			classes.stream().filter(SystemComponent::isInner).forEach(out.classes::add);
			return out;
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
			if (!isInner() || !callsIn.isEmpty() || !root.getChildClasses().isEmpty()) return false;

			if (!root.getFieldTypeRefs().isEmpty() || root.getMethodTypeRefs().stream().filter(method -> method.getCls() != root).count() != 1) return false;

			assert root.getSuperClass() != root.getEnv().getClsById("Ljava/lang/Object;") || root.getInterfaces().size() == 1: "Suspicous anonymous class: " + root;
			return true;
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

	private static List<ClassSystem> findSystems(Stream<ClassInstance> classes, Predicate<ClassInstance> isAside, DoubleConsumer progress) {
		List<ClassSystem> systems = new ArrayList<>();

		Matcher.runInParallel(classes.filter(cls -> cls.getUri() != null && cls.isInput() && cls.isNameObfuscated()).collect(Collectors.toList()), cls -> {
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
						System.out.println("Found likely inner class from " + cls.getName() + '#' + method.getName() + method.getDesc() + " (" + (isAside.test(cls) ? "A" : "B") + " side)");

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

		return systems;
	}

	private static void findInnerClasses(Gui gui, DoubleConsumer progress) {
		List<ClassSystem> systems = findSystems(Stream.concat(gui.getEnv().getClassesA().stream(), gui.getEnv().getClassesB().stream()), gui.getEnv().getClassesA()::contains, progress);

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

	private static void fixSimpleInners(Gui gui, Side side, DoubleConsumer progress) {
		Collection<ClassInstance> startingClasses;
		switch (side) {
		case A:
			startingClasses = gui.getEnv().getClassesA();
			break;

		case B:
			startingClasses = gui.getEnv().getClassesB();
			break;

		case BOTH:
			startingClasses = gui.getEnv().getClasses();
			break;

		default:
			throw new IllegalStateException("Unexpected side: " + side);
		}

		int fixedSystems = 0;
		Map<ClassInstance, SystemComponent> fixedComponents = new IdentityHashMap<>();

		Map<ClassInstance, Integer> anonymousPool = new IdentityHashMap<>();
		Map<ClassInstance, Integer> anonymousMatched = new IdentityHashMap<>();

		List<ClassSystem> systems = findSystems(startingClasses.stream().filter(cls -> !cls.hasMappedName()), gui.getEnv().getClassesA()::contains, progress);
		for (ClassSystem system : systems) {
			if (!system.isSimple()) {
				if (!system.canBeMadeSimple()) continue;
				system = system.makeSimple();
			}

			if (system.isVacuous()) {
				System.out.println("Unhelpful (simple) system: " + system);
				continue;
			}

			for (SystemComponent component : system.classes) {
				boolean anonymous = component.isAnonymous();

				ClassInstance outer = null;
				for (MethodInstance method : component.root.getMethods()) {
					if ("<init>".equals(method.getName())) {
						if (anonymous) {
							Set<MethodInstance> refsIn = method.getRefsIn();
							assert refsIn.size() == 1: "Found multiple references to " + method + ": " + refsIn;

							assert outer == null;
							outer = refsIn.iterator().next().getCls();
						} else {
							MethodVarInstance[] args = method.getArgs();
							assert args.length != 0; //A local class needs a reference to the outer class (otherwise it's nested not local)

							if (outer == null) {
								outer = args[0].getType();
							} else {
								assert outer == args[0].getType();
							}
						}

					}
				}
				assert outer != null; //It must have a constructor somewhere (aside from a few edge cases where they're stripped, but hopefully not for local classes)

				enforceHierarchy(outer, component.root);
				if (anonymous) {
					Integer id;
					if (component.root.hasMatch() && anonymousMatched.containsKey(component.root.getMatch())) {
						id = anonymousMatched.get(component.root.getMatch());
					} else {
						id = anonymousPool.compute(outer, (key, last) -> last == null ? 1 : last + 1);
						if (component.root.hasMatch()) anonymousMatched.put(component.root, id);
					}
					component.root.setMappedName(id.toString());
				}

				fixedComponents.put(component.root, component);
			}

			fixedSystems++;
		}

		System.out.println("Fixed " + fixedComponents.size() + " inner classes from " + fixedSystems + " systems");
	}
}