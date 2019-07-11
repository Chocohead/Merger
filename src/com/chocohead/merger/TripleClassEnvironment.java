package com.chocohead.merger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import matcher.NameType;
import matcher.Util;
import matcher.config.ProjectConfig;
import matcher.srcprocess.Decompiler;
import matcher.type.ClassEnvironment;
import matcher.type.ClassFeatureExtractor;
import matcher.type.ClassInstance;
import matcher.type.InputFile;
import matcher.type.LocalClassEnv;

public class TripleClassEnvironment extends ClassEnvironment {
	protected final ClassFeatureExtractor extractorC = new ClassFeatureExtractor(this);
	protected Pattern nonObfuscatedClassPatternC;
	protected Pattern nonObfuscatedMemberPatternC;
	private final ClassEnvironment real;
	private final boolean aSide;

	public TripleClassEnvironment(ClassEnvironment real, boolean aSide) {
		this.real = real;
		this.aSide = aSide;
	}

	@Override
	public void init(ProjectConfig config, DoubleConsumer progressReceiver) {
		throw new UnsupportedOperationException("Wrong init");
	}

	public void init(List<Path> pathsC, List<Path> classPathC, ProjectConfig config, DoubleConsumer progressReceiver) {
		init(pathsC, classPathC, config.getSharedClassPath(), "", "", config.hasInputsBeforeClassPath(), progressReceiver);
	}

	public void init(List<Path> pathsC, List<Path> classPathC, List<Path> commonClassPath, String nonObfClassPattern, String nonObfMemberPattern, boolean inputsBeforeClassPath, DoubleConsumer progressReceiver) {
		final double cpInitCost = 0.1;
		final double classReadCost = 0.4;
		double progress = 0;

		nonObfuscatedClassPatternC = nonObfClassPattern.isEmpty() ? null : Pattern.compile(nonObfClassPattern);
		nonObfuscatedMemberPatternC = nonObfMemberPattern.isEmpty() ? null : Pattern.compile(nonObfMemberPattern);

		try {
			for (int i = 0; i < 2; i++) {
				if (i == 0 != inputsBeforeClassPath) {
					initClassPath(commonClassPath, inputsBeforeClassPath);
					extractorC.processClassPath(classPathC, inputsBeforeClassPath);
					progress += cpInitCost;
				} else {
					extractorC.processInputs(pathsC, nonObfuscatedClassPatternC);
					progress += classReadCost;
				}

				progressReceiver.accept(progress);
			}

			extractorC.process(nonObfuscatedMemberPatternC);
			progressReceiver.accept(0.95);

			Field classPathIndex = ClassEnvironment.class.getDeclaredField("classPathIndex");
			classPathIndex.setAccessible(true);
			((Map<?, ?>) classPathIndex.get(this)).clear();

			Field openFileSystemsField = ClassEnvironment.class.getDeclaredField("openFileSystems");
			openFileSystemsField.setAccessible(true);
			@SuppressWarnings("unchecked")
			Collection<FileSystem> openFileSystems = (Collection<FileSystem>) openFileSystemsField.get(this);

			openFileSystems.forEach(Util::closeSilently);
			openFileSystems.clear();
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException("Error cleaning up initialisation", e);
		}

		progressReceiver.accept(1);
	}

	private void initClassPath(Collection<Path> sharedClassPath, boolean checkExisting) {
		try {
			LocalClassEnv otherExtractor = getEnvB();
			Collection<InputFile> cpFiles = getClassPathFiles();

			Constructor<InputFile> newInputFile = InputFile.class.getDeclaredConstructor(Path.class);
			newInputFile.setAccessible(true);

			Field classPathIndexField = ClassEnvironment.class.getDeclaredField("classPathIndex");
			classPathIndexField.setAccessible(true);
			@SuppressWarnings("unchecked")
			Map<String, Path> classPathIndex = (Map<String, Path>) classPathIndexField.get(this);

			for (Path archive : sharedClassPath) {
				cpFiles.add(newInputFile.newInstance(archive));

				addOpenFileSystem(Util.iterateJar(archive, false, file -> {
					String name = file.toAbsolutePath().toString();
					if (!name.startsWith("/") || !name.endsWith(".class") || name.startsWith("//")) throw new RuntimeException("Invalid path: " + archive + " (" + name + ')');
					name = name.substring(1, name.length() - ".class".length());

					if (!checkExisting || extractorC.getLocalClsByName(name) == null || otherExtractor.getLocalClsByName(name) == null) {
						classPathIndex.putIfAbsent(name, file);
					}
				}));
			}
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException("Error initialising the classpath", e);
		}
	}

	@Override
	public void reset() {
		//We could support reset, but at that point it becomes questionable what to do for the real one
		throw new UnsupportedOperationException("C sided environments shouldn't be reset");
	}

	@Override
	public Pattern getNonObfuscatedClassPatternA() {
		return nonObfuscatedClassPatternC;
	}

	@Override
	public Pattern getNonObfuscatedClassPatternB() {
		return aSide ? real.getNonObfuscatedClassPatternA() : real.getNonObfuscatedClassPatternB();
	}

	@Override
	public Pattern getNonObfuscatedMemberPatternA() {
		return nonObfuscatedMemberPatternC;
	}

	@Override
	public Pattern getNonObfuscatedMemberPatternB() {
		return aSide ? real.getNonObfuscatedMemberPatternA() : real.getNonObfuscatedMemberPatternB();
	}

	@Override
	public LocalClassEnv getEnvA() {
		return extractorC;
	}

	@Override
	public LocalClassEnv getEnvB() {
		return aSide ? real.getEnvA() : real.getEnvB();
	}

	@Override
	public Collection<ClassInstance> getClasses() {
		return new AbstractCollection<ClassInstance>() {
			@Override
			public Iterator<ClassInstance> iterator() {
				return new Iterator<ClassInstance>() {
					@Override
					public boolean hasNext() {
						checkAdvance();

						return parent.hasNext();
					}

					@Override
					public ClassInstance next() {
						checkAdvance();

						return parent.next();
					}

					private void checkAdvance() {
						if (isA && !parent.hasNext()) {
							parent = getClassesB().iterator();
							isA = false;
						}
					}

					private Iterator<ClassInstance> parent = getClassesA().iterator();
					private boolean isA = true;
				};
			}

			@Override
			public int size() {
				return getClassesA().size() + getClassesB().size();
			}
		};
	}

	@Override
	public ClassInstance getClsByNameA(String name) {
		return extractorC.getClsByName(name);
	}

	@Override
	public ClassInstance getClsByNameB(String name) {
		return aSide ? real.getClsByNameA(name) : real.getClsByNameB(name);
	}

	@Override
	public ClassInstance getClsByIdA(String id) {
		return extractorC.getClsById(id);
	}

	@Override
	public ClassInstance getClsByIdB(String id) {
		return aSide ? real.getClsByIdA(id) : real.getClsByIdB(id);
	}

	@Override
	public ClassInstance getLocalClsByNameA(String name) {
		return extractorC.getLocalClsByName(name);
	}

	@Override
	public ClassInstance getLocalClsByNameB(String name) {
		return aSide ? real.getLocalClsByNameA(name) : real.getLocalClsByNameB(name);
	}

	@Override
	public ClassInstance getLocalClsByIdA(String id) {
		return extractorC.getLocalClsById(id);
	}

	@Override
	public ClassInstance getLocalClsByIdB(String id) {
		return aSide ? real.getLocalClsByIdA(id) : real.getLocalClsByIdB(id);
	}

	@Override
	public ClassInstance getSharedClsById(String id) {
		ClassInstance out = real.getSharedClsById(id);
		if (out != null) return out;

		return super.getSharedClsById(id);
	}

	@Override
	public ClassInstance addSharedCls(ClassInstance cls) {
		//throw new UnsupportedOperationException("Tried to add shared class: " + cls);
		return super.addSharedCls(cls);
	}

	@Override
	public Path getSharedClassLocation(String name) {
		return real.getSharedClassLocation(name);
	}

	@Override
	public Collection<ClassInstance> getClassesA() {
		return extractorC.getClasses();
	}

	@Override
	public Collection<ClassInstance> getClassesB() {
		return aSide ? real.getClassesA() : real.getClassesB();
	}

	@Override
	public List<ClassInstance> getDisplayClassesA(boolean inputsOnly) {
		return extractorC.getClasses().stream().filter(cls -> cls.getUri() != null && (!inputsOnly || cls.isInput())).sorted(Comparator.comparing(ClassInstance::toString)).collect(Collectors.toList());
	}

	@Override
	public List<ClassInstance> getDisplayClassesB(boolean inputsOnly) {
		return aSide ? real.getDisplayClassesA(inputsOnly) : real.getDisplayClassesB(inputsOnly);
	}

	@Override
	public Collection<InputFile> getClassPathFiles() {
		return real.getClassPathFiles();
	}

	@Override
	public Collection<InputFile> getClassPathFilesA() {
		return extractorC.getClassPathFiles();
	}

	@Override
	public Collection<InputFile> getClassPathFilesB() {
		return aSide ? real.getClassPathFilesA() : real.getClassPathFilesB();
	}

	@Override
	public Collection<InputFile> getInputFilesA() {
		return extractorC.getInputFiles();
	}

	@Override
	public Collection<InputFile> getInputFilesB() {
		return aSide ? real.getInputFilesA() : real.getInputFilesB();
	}

	@Override
	public String decompile(Decompiler decompiler, ClassInstance cls, NameType nameType) {
		LocalClassEnv env;
		if ((env = getEnvA()).getLocalClsById(cls.getId()) == cls || (env = getEnvB()).getLocalClsById(cls.getId()) == cls) {
			return decompiler.decompile(cls, (ClassFeatureExtractor) env, nameType);
		} else {
			throw new IllegalArgumentException("unknown class: "+cls);
		}
	}
}