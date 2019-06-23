package com.chocohead.merger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import matcher.Matcher;

public class UnsharedMatcher {
	private static final MethodHandle runInParallel;
	static {
		try {
			Method m = Matcher.class.getDeclaredMethod("runInParallel", List.class, Consumer.class, DoubleConsumer.class);
			m.setAccessible(true);
			runInParallel = MethodHandles.lookup().unreflect(m);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException("Error finding runInParallel", e);
		}
	}
	private static final MethodHandle sanitizeMatches;
	static {
		try {
			Method m = Matcher.class.getDeclaredMethod("sanitizeMatches", Map.class);
			m.setAccessible(true);
			sanitizeMatches = MethodHandles.lookup().unreflect(m);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException("Error finding sanitizeMatches", e);
		}
	}

	public static <T> void runParallel(List<T> workSet, Consumer<T> worker, DoubleConsumer progressReceiver) {
		try {
			runInParallel.invokeExact(workSet, worker, progressReceiver);
		} catch (Throwable t) {
			throw new RuntimeException("Error running in parallel", t);
		}
	}

	public static <T> void sanitise(Map<T, T> matches) {
		try {
			sanitizeMatches.invokeExact(matches);
		} catch (Throwable t) {
			throw new RuntimeException("Error sanitising matches", t);
		}
	}
}