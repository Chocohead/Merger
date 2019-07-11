package com.chocohead.merger;

import java.util.Iterator;

public class QueuingIterator<T> implements Iterator<T> {//Not the best of class names
	private final T[] start;
	private final T end;
	private int pass;

	public QueuingIterator(T[] start, T end) {
		this.start = start;
		this.end = end;
	}

	@Override
	public boolean hasNext() {
		return true;
	}

	public boolean keepGoing() {
		return pass <= start.length;
	}

	@Override
	public T next() {
		if (pass < start.length) {
			return start[pass++];
		} else {
			if (keepGoing()) pass++;
			return end;
		}
	}
}