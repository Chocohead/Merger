package com.chocohead.merger.menu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

	private void findInnerClasses(Gui gui, DoubleConsumer progress) {
		List<ClassInstance> classes = Stream.concat(gui.getEnv().getClassesA().stream(), gui.getEnv().getClassesB().stream()).filter(cls -> cls.getUri() != null && cls.isNameObfuscated()).collect(Collectors.toList());
		Set<ClassInstance> localClasses = Collections.newSetFromMap(new ConcurrentHashMap<>());
		Map<ClassInstance, Set<ClassInstance>> innerClasses = new ConcurrentHashMap<>();

		UnsharedMatcher.runParallel(classes, cls -> {
			if ((cls.getAccess() & Opcodes.ACC_SYNTHETIC) != 0) return; //Synthetic enum switch class

			if (!cls.isEnum()) {//Don't go looking for synthetic fields in an enum as they're always static but will still have one ($VALUES)

				for (FieldInstance field : cls.getFields()) {
					if (field.isReal() && (field.getAccess() & Opcodes.ACC_SYNTHETIC) != 0) {
						assert Arrays.stream(cls.getMethods()).filter(method -> "<init>".equals(method.getName())).count() == 1: "Multiple constructors found in " + cls.getName();
						localClasses.add(cls);
						return; //If it has a synthetic field cls is probably a local class of some kind
					}
				}
			}

			for (MethodInstance method : cls.getMethods()) {
				if (method.isReal() && (method.getAccess() & Opcodes.ACC_SYNTHETIC) != 0) {
					Set<MethodInstance> refsIn = method.getRefsIn();

					switch (refsIn.size()) {
					case 0:
						//Appears unused, probably called as a bridge override of something else
						break;

					case 1:
						MethodInstance refIn = refsIn.iterator().next();
						System.out.println("Found likely inner class from " + refIn + " (calling " + method + ')');
						innerClasses.computeIfAbsent(cls, k -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(refIn.getCls());
						break;

					default:
						//Lots of uses for a synthetic, strange
						System.out.println("Multiple references found for " + cls.getName() + '#' + method.getName() + method.getDesc() + " (" + (gui.getEnv().getClassesA().contains(cls) ? "server" : "client") + " side)");
						Set<ClassInstance> pool = innerClasses.computeIfAbsent(cls, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()));
						refsIn.stream().map(MethodInstance::getCls).distinct().forEach(pool::add);
					}
				}
			}
		}, progress::accept);

		System.out.println();/*
		System.out.println("Found " + localClasses.size() + " local classes and " + innerClasses.size() + " (likely) inner classes, " + localClasses.stream().filter(cls -> innerClasses.stream().anyMatch(entry -> entry.getValue() == cls)).count() + " common to both");
		System.out.println("Common:");
		localClasses.stream().filter(cls -> innerClasses.values().stream().flatMap(Set::stream).anyMatch(entry -> entry.getValue() == cls)).filter(gui.getEnv().getClassesA()::contains).sorted(Comparator.comparing(ClassInstance::getName)).forEach(cls -> System.out.println(cls.getName()));
		System.out.println("Local classes:");
		localClasses.stream().filter(cls -> innerClasses.stream().noneMatch(entry -> entry.getValue() == cls)).filter(gui.getEnv().getClassesA()::contains).sorted(Comparator.comparing(ClassInstance::getName)).forEach(cls -> System.out.println(cls.getName()));
		System.out.println("Inner classes:");
		innerClasses.stream().filter(entry -> !localClasses.contains(entry.getValue())).filter(entry -> gui.getEnv().getClassesA().contains(entry.getValue())).sorted(Comparator.comparing(Entry::getValue, Comparator.comparing(ClassInstance::getName))).forEach(entry -> System.out.println(entry.getKey().getName() + " => " + entry.getValue().getName()));*/

		Map<ClassInstance, Set<ClassInstance>> seenInners = new IdentityHashMap<>();
		on: for (Entry<ClassInstance, Set<ClassInstance>> entry : innerClasses.entrySet()) {
			if (seenInners.containsKey(entry.getKey())) {
				seenInners.get(entry.getKey()).addAll(entry.getValue());
				seenInners.get(entry.getKey()).remove(entry.getKey()); //A class won't contain itself
			} else {
				for (ClassInstance cls : entry.getValue()) {
					if (seenInners.containsKey(cls)) {
						Set<ClassInstance> pool = seenInners.get(cls);

						pool.addAll(entry.getValue());
						pool.add(entry.getKey());
						pool.remove(cls);

						continue on;
					}
				}

				seenInners.put(entry.getKey(), Util.newIdentityHashSet(entry.getValue()));
			}
		}

		System.out.println("Found inner class collections:");
		for (Entry<ClassInstance, Set<ClassInstance>> entry : seenInners.entrySet()) {
			System.out.print(Stream.concat(Stream.of(entry.getKey()), entry.getValue().stream()).map(cls -> localClasses.contains(cls) ? '(' + cls.getName() + ')' : cls.getName()).collect(Collectors.joining(", ", "\t[", "]")));

			Set<ClassInstance> extra = Util.newIdentityHashSet();
			for (ClassInstance cls : entry.getValue()) {
				List<ClassInstance> parents = new ArrayList<>();

				ClassInstance parent = cls;
				while ((parent = parent.getSuperClass()) != null) {
					if (!entry.getValue().contains(parent)) {
						parents.add(parent);
					} else {
						extra.addAll(parents);
						break;
					}
				}
			}

			if (!extra.isEmpty()) {
				System.out.println(", likely with " + extra);
			} else {
				System.out.println();
			}
		}
		System.out.println();
		System.out.println("Local classes:");
		localClasses.stream().map(ClassInstance::getName).sorted().forEach(System.out::println);
	}
}